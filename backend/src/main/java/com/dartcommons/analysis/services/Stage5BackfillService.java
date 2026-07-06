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
 * [목적] 기존 stage_reached=4(Stage 4 완료) 분석 결과에 Stage 5 재무 분석을 소급 적용하는 관리자 잡.
 *       Stage5Analyzer.analyze()를 커서 페이지네이션으로 호출 — skip 조건(스냅샷없음·withheld)은 Analyzer가 처리.
 * [이유] analysis-stage5-financial-industry Spec 카드 #11. Stage4BackfillService 패턴 동일 답습.
 *       파이프라인 연결 전 Stage 4 완료된 분석은 신규 폴링으로 Stage 5가 적용되지 않음.
 * [사이드 임팩트] AnalysisResult UPDATE만 수행(stage_details 병합 + stage_reached=5) — feedbacks FK 안전.
 *               조기 중단: 연속 LLM 예외 30건(성공 시 리셋) — OpenRouter 쿼터 소진 시 예산 보호(Stage4와 동일).
 *               재무 스냅샷 시드 백필(POST /admin/financial/seed-backfill)이 선행돼야 실효 — 스냅샷 없으면 전건 skip.
 * [수정 시 고려사항] CHUNK_SIZE=50: LLM 호출 잡 보수 기준(Stage4와 동일).
 *                  재개는 stage_reached=4 필터 기반 멱등 — 완료 건(stage=5)은 쿼리에서 자동 제외.
 */
@Service
public class Stage5BackfillService {

    private static final Logger log = LoggerFactory.getLogger(Stage5BackfillService.class);
    private static final int CHUNK_SIZE = 50;

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AnalysisJobRepository jobRepo;
    private final AnalysisResultRepository resultRepo;
    private final Stage5Analyzer stage5Analyzer;

    public Stage5BackfillService(AnalysisJobRepository jobRepo,
                                 AnalysisResultRepository resultRepo,
                                 Stage5Analyzer stage5Analyzer) {
        this.jobRepo = jobRepo;
        this.resultRepo = resultRepo;
        this.stage5Analyzer = stage5Analyzer;
    }

    /** 잡 원자 생성 + 비동기 실행. 이미 실행 중이면 Optional.empty() → 호출자 409. */
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
        AnalysisJob job = jobRepo.save(AnalysisJob.create(AnalysisStage.FINANCIAL, null, null, CHUNK_SIZE));
        log.info("Stage5BackfillJob created: jobId={}", job.getJobId());
        return job;
    }

    @Async("analysisBackfillExecutor")
    public void runAsync(UUID jobId) {
        try {
            doBackfill(jobId);
        } catch (Exception e) {
            failJob(jobId, e.getMessage());
            log.error("Stage5BackfillService: job failed jobId={}", jobId, e);
        } finally {
            running.set(false);
        }
    }

    /** 테스트용 동기 진입점. */
    public void doBackfillSynchronous(UUID jobId) {
        doBackfill(jobId);
    }

    private void doBackfill(UUID jobId) {
        long total = resultRepo.countStage5BackfillTargets();
        int safetyCap = (int) ((total / Math.max(CHUNK_SIZE, 1) + 2) * 2);
        int estimatedChunks = (int) Math.ceil(total / (double) CHUNK_SIZE);
        startJob(jobId, estimatedChunks, (int) total);
        log.info("Stage5BackfillService: start jobId={} targeted={} estimatedChunks={}", jobId, total, estimatedChunks);

        Long lastId = null;
        int processed = 0, skipped = 0, actualFailed = 0, consecutiveFailed = 0;

        for (int i = 0; i < safetyCap; i++) {
            List<Long> ids = resultRepo.findStage5BackfillTargets(lastId, PageRequest.of(0, CHUNK_SIZE));
            if (ids.isEmpty()) break;

            int chunkProcessed = 0, chunkSkipped = 0, chunkFailed = 0;
            for (Long disclosureId : ids) {
                try {
                    Optional<?> res = stage5Analyzer.analyze(disclosureId);
                    if (res.isPresent()) {
                        chunkProcessed++;
                        consecutiveFailed = 0;
                    } else {
                        chunkSkipped++;   // 정상 skip(스냅샷없음·withheld·already-stage5)
                    }
                } catch (Exception e) {
                    log.warn("Stage5Backfill: 단건 실패 disclosureId={} err={}", disclosureId, e.getMessage());
                    chunkFailed++;
                    consecutiveFailed++;
                    if (consecutiveFailed >= 30) {
                        recordProgress(jobId, chunkProcessed, chunkFailed);
                        throw new IllegalStateException(
                                "Stage5Backfill 조기 중단: 연속 LLM 예외 30건. OpenRouter 쿼터/가용성 확인 필요.");
                    }
                }
            }
            processed += chunkProcessed;
            skipped += chunkSkipped;
            actualFailed += chunkFailed;
            lastId = ids.get(ids.size() - 1);
            recordProgress(jobId, chunkProcessed, chunkFailed);
            log.info("Stage5Backfill: chunk done processed={}/{} skipped={} failed={} lastId={}",
                    processed, total, chunkSkipped, chunkFailed, lastId);
        }

        succeedJob(jobId);
        log.info("Stage5BackfillService: done jobId={} processed={} skipped={} failed={}", jobId, processed, skipped, actualFailed);
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
    public void recordProgress(UUID jobId, int processedDelta, int failedDelta) {
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
