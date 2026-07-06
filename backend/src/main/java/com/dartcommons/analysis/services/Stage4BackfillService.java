package com.dartcommons.analysis.services;

import com.dartcommons.shared.enums.AnalysisStage;
import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.analysis.repositories.AnalysisJobRepository;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * [목적] 기존 stage_reached=2(Stage 2 완료) 분석 결과에 Stage 4 판단을 소급 적용하는 관리자 잡.
 *       Stage4Analyzer.analyze()를 커서 페이지네이션으로 호출 — 각 건의 skip 조건(withheld·유사표본 없음)은 Analyzer가 처리.
 * [이유] analysis-stage4-llm-final Spec R7: 파이프라인 연결 전 수집된 공시(~19,609건)는 신규 폴링으로 Stage 4가 적용되지 않음.
 *       Spec 결정 3(서버 야간 분할): LLM이 외부 API(OpenRouter)라 로컬 실행 이점 없음.
 * [사이드 임팩트] AnalysisResult UPDATE만 수행 — INSERT/DELETE 없으므로 feedbacks FK 안전.
 *               Stage4Analyzer.analyze 내부에서 stage_reached < 4 체크 → 이미 Stage 4인 건 자동 skip(멱등).
 *               조기 중단: 연속 LLM 예외 30건(성공 시 리셋) → IllegalStateException. OpenRouter는 실패 요청도
 *               일 쿼터를 차감하므로, 쿼터 소진 상태에서 잔여 대상 전체를 헛돌며 예산을 태우는 것을 차단.
 * [수정 시 고려사항] CHUNK_SIZE=50: Stage 4는 LLM 호출이므로 Stage 3(100)보다 보수적으로 설정.
 *                  재개는 커서 영속이 아니라 stage_reached=2 필터 기반 멱등 — 재시작 후 POST 재호출 시
 *                  완료 건(stage=4)은 쿼리에서 제외되고, skip 건은 재스캔되나 LLM 미호출(Chroma 조회만)이라 예산 무손실.
 *                  진짜 커서(last_processed_id) 영속이 필요해지면 analysis_jobs 컬럼 추가(Flyway) 검토.
 */
@Service
public class Stage4BackfillService {

    private static final Logger log = LoggerFactory.getLogger(Stage4BackfillService.class);
    private static final int CHUNK_SIZE = 50;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AnalysisJobRepository jobRepo;
    private final AnalysisResultRepository resultRepo;
    private final Stage4Analyzer stage4Analyzer;

    public Stage4BackfillService(AnalysisJobRepository jobRepo,
                                 AnalysisResultRepository resultRepo,
                                 Stage4Analyzer stage4Analyzer) {
        this.jobRepo = jobRepo;
        this.resultRepo = resultRepo;
        this.stage4Analyzer = stage4Analyzer;
    }

    /**
     * 잡 원자 생성 + 비동기 실행. 이미 실행 중이면 Optional.empty() → 호출자 409.
     */
    public Optional<AnalysisJob> createAndStartAsync() {
        if (!running.compareAndSet(false, true)) return Optional.empty();
        try {
            AnalysisJob job = createJob();
            runAsync(job.getJobId());
            return Optional.of(job);
        } catch (Exception e) {
            running.set(false);
            throw e;
        }
    }

    @Transactional
    public AnalysisJob createJob() {
        // stage=4로 잡 생성 — analysis_jobs.stage 컬럼 재사용(AnalysisJob.create 시그니처 답습)
        AnalysisJob job = jobRepo.save(AnalysisJob.create(AnalysisStage.LLM_FINAL, null, null, CHUNK_SIZE));
        log.info("Stage4BackfillJob created: jobId={}", job.getJobId());
        return job;
    }

    @Async("analysisBackfillExecutor")
    public void runAsync(UUID jobId) {
        try {
            doBackfill(jobId);
        } catch (Exception e) {
            failJob(jobId, e.getMessage());
            log.error("Stage4BackfillService: job failed jobId={}", jobId, e);
        } finally {
            running.set(false);
        }
    }

    /** 테스트용 동기 진입점. */
    public void doBackfillSynchronous(UUID jobId) {
        doBackfill(jobId);
    }

    private void doBackfill(UUID jobId) {
        long total = resultRepo.countStage4BackfillTargets();
        int safetyCap = (int) ((total / Math.max(CHUNK_SIZE, 1) + 2) * 2);

        int estimatedChunks = (int) Math.ceil(total / (double) CHUNK_SIZE);
        startJob(jobId, estimatedChunks, (int) total);
        log.info("Stage4BackfillService: start jobId={} targeted={} estimatedChunks={}", jobId, total, estimatedChunks);

        Long lastId = null;
        // ponytail: skipped(표본없음·withheld·already-stage4)과 LLM예외를 분리 — Optional.empty()는 정상 skip.
        // consecutiveFailed는 성공 시 리셋 — 중간 쿼터 소진(429 연속)도 감지해 예산 헛소모 차단.
        int processed = 0, skipped = 0, actualFailed = 0, consecutiveFailed = 0;

        for (int i = 0; i < safetyCap; i++) {
            List<Long> ids = resultRepo.findStage4BackfillTargets(lastId, PageRequest.of(0, CHUNK_SIZE));
            if (ids.isEmpty()) break;

            int chunkProcessed = 0, chunkSkipped = 0, chunkFailed = 0;
            for (Long disclosureId : ids) {
                try {
                    Optional<?> res = stage4Analyzer.analyze(disclosureId);
                    if (res.isPresent()) {
                        chunkProcessed++;
                        consecutiveFailed = 0;    // 성공 → 연속 실패 리셋
                    } else {
                        chunkSkipped++;           // 정상 skip(표본없음·withheld·already-stage4) — 연속 실패에 미포함
                    }
                } catch (Exception e) {
                    log.warn("Stage4Backfill: 단건 실패 disclosureId={} err={}", disclosureId, e.getMessage());
                    chunkFailed++;
                    consecutiveFailed++;
                    // 조기 중단: 연속 LLM 예외 30건 → OpenRouter 쿼터 소진/가용성 문제.
                    // 실패 요청도 일 쿼터를 차감하므로 즉시 중단해 예산 보호. 재시작 후 POST 재호출로 멱등 재개.
                    if (consecutiveFailed >= 30) {
                        recordProgress(jobId, chunkProcessed, chunkFailed, lastId);
                        throw new IllegalStateException(
                                "Stage4Backfill 조기 중단: 연속 LLM 예외 30건. OpenRouter 쿼터/가용성 확인 필요.");
                    }
                }
            }
            processed += chunkProcessed;
            skipped += chunkSkipped;
            actualFailed += chunkFailed;
            lastId = ids.get(ids.size() - 1);
            recordProgress(jobId, chunkProcessed, chunkFailed, lastId);
            log.info("Stage4Backfill: chunk done processed={}/{} skipped={} failed={} lastId={}",
                    processed, total, chunkSkipped, chunkFailed, lastId);
        }

        succeedJob(jobId);
        log.info("Stage4BackfillService: done jobId={} processed={} skipped={} failed={}", jobId, processed, skipped, actualFailed);
    }

    public boolean isRunning() { return running.get(); }

    @Transactional(readOnly = true)
    public Optional<AnalysisJob> findByJobId(UUID jobId) {
        return jobRepo.findByJobId(jobId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startJob(UUID jobId, int chunksTotal, int targeted) {
        jobRepo.findByJobId(jobId).ifPresent(j -> { j.start(chunksTotal, targeted); jobRepo.save(j); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProgress(UUID jobId, int processedDelta, int failedDelta, Long lastId) {
        jobRepo.findByJobId(jobId).ifPresent(j -> { j.recordChunkProgress(processedDelta, failedDelta); jobRepo.save(j); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedJob(UUID jobId) {
        jobRepo.findByJobId(jobId).ifPresent(j -> { j.succeed(); jobRepo.save(j); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(UUID jobId, String error) {
        jobRepo.findByJobId(jobId).ifPresent(j -> { j.fail(error); jobRepo.save(j); });
    }
}
