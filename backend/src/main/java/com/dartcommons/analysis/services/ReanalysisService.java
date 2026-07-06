package com.dartcommons.analysis.services;

import com.dartcommons.shared.enums.AnalysisStage;
import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.shared.enums.AnalysisStage;
import com.dartcommons.analysis.repositories.AnalysisJobRepository;
import com.dartcommons.shared.enums.AnalysisStage;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * [목적] charset 재수집(content_fetched_at >= since)으로 본문이 정정된 공시들의 기존 분석 결과를
 *       청크 단위로 선삭제 → Stage2Analyzer 재분석해 수치·해설 정합성을 확보한다.
 *       AnalysisBackfillService가 "미분석 공시"만 처리하므로, 이미 분석 결과가 있는 공시는
 *       이 서비스로 삭제 후 재분석해야 Stage2Analyzer의 existsByDisclosureId 스킵이 작동하지 않는다.
 * [이유] content-text-charset-mojibake 재수집으로 content_text는 정정됐으나 기존 analysis_results는
 *       손상 본문 기반 값(실측: 94128 "448조원" 환각)이 그대로 잔존. 투자자에게 틀린 수치 노출(§11 리스크).
 *       선삭제 후 AnalysisBackfillService.runAsync 재사용 패턴을 채택해 코드 중복 최소화.
 * [사이드 임팩트] feedbacks.analysis_id ON DELETE CASCADE — feedbacks 0건(MVP) 무손실.
 *               feedbacks 존재 시 cascade 유실 → 프로드 전 접근 B(UPDATE overwrite) 검토 필요.
 *               캐시 evict: 청크 삭제 직후 AnalysisResultCacheService가 개별 캐시 보유 시 stale.
 *               Stage2Analyzer.analyze()가 트랜잭션 내부에서 LLM 호출 → 오래 걸릴 수 있음.
 *               노출 공백: 선삭제~재분석 사이 해당 공시 분석이 "없음" 상태 → 청크 단위로 최소화.
 *               analysisBackfillExecutor(core1/max2)를 재사용 — 폴링 SLO 보호 유지.
 * [수정 시 고려사항] since 파라미터: 재수집 일자 기준(2026-07-03T00:00Z). 확장 시 idFrom/idTo 범위도 수용 가능.
 *                  접근 B(feedbacks 보존): Stage2Analyzer에 overwrite 모드 추가 후 이 서비스도 수정 대상.
 *                  AnalysisResultCacheService.evict 명시 호출: 현재 ON DELETE CASCADE 처리는 DB 레벨,
 *                  캐시는 TTL(10분) 자연 만료에 의존 — 즉시 정합 필요 시 직접 evict 추가.
 *                  stale-RUNNING 잡 자동 재개: analysis_jobs에는 content-backfill과 달리 자동 재개 없음 —
 *                  중단 시 수동 재트리거(watermark로 이미 재분석된 건 스킵).
 */
@Service
public class ReanalysisService {

    private static final Logger log = LoggerFactory.getLogger(ReanalysisService.class);

    private final AtomicBoolean running = new AtomicBoolean(false);

    private final AnalysisJobRepository jobRepo;
    private final AnalysisResultRepository resultRepo;
    private final Stage2Analyzer stage2Analyzer;
    private final TransactionTemplate txTemplate;

    public ReanalysisService(AnalysisJobRepository jobRepo,
                             AnalysisResultRepository resultRepo,
                             Stage2Analyzer stage2Analyzer,
                             TransactionTemplate txTemplate) {
        this.jobRepo = jobRepo;
        this.resultRepo = resultRepo;
        this.stage2Analyzer = stage2Analyzer;
        this.txTemplate = txTemplate;
    }

    /**
     * 재분석 잡 생성 + 비동기 실행 시작.
     * 이미 실행 중이면 Optional.empty() 반환(호출자가 409 처리).
     */
    public Optional<AnalysisJob> createAndStartAsync(OffsetDateTime since, int chunkSize) {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            AnalysisJob job = createJob(since, chunkSize);
            UUID jobId = job.getJobId();
            runAsync(jobId, since, chunkSize);
            return Optional.of(job);
        } catch (Exception e) {
            running.set(false);
            throw e;
        }
    }

    @Transactional
    public AnalysisJob createJob(OffsetDateTime since, int chunkSize) {
        // stage=2, idFrom=null(전체 범위 — since 조건으로 대체), idTo=null
        AnalysisJob job = jobRepo.save(AnalysisJob.create(AnalysisStage.LLM_CLASSIFY, null, null, chunkSize));
        log.info("ReanalysisJob created: jobId={} since={} chunkSize={}", job.getJobId(), since, chunkSize);
        return job;
    }

    @Async("analysisBackfillExecutor")
    public void runAsync(UUID jobId, OffsetDateTime since, int chunkSize) {
        try {
            doReanalyze(jobId, since, chunkSize);
        } catch (Exception e) {
            failJob(jobId, e.getMessage());
            log.error("ReanalysisService: job failed jobId={}", jobId, e);
        } finally {
            running.set(false);
        }
    }

    /** 테스트용 동기 실행 진입점 — @Async 타이밍 없이 직접 호출. 운영에서는 runAsync 사용. */
    public void doReanalyzeSynchronous(UUID jobId, OffsetDateTime since, int chunkSize) {
        doReanalyze(jobId, since, chunkSize);
    }

    private void doReanalyze(UUID jobId, OffsetDateTime since, int chunkSize) {
        long total = resultRepo.countReanalysisTargets(since);
        int chunksTotal = (int) Math.ceil(total / (double) chunkSize);
        int safetyCap = (chunksTotal + 1) * 2;

        startJob(jobId, chunksTotal, (int) total);
        log.info("ReanalysisService: start jobId={} targeted={} since={} chunks={}", jobId, total, since, chunksTotal);

        Long watermark = null;
        int analyzedCum = 0, failedCum = 0;

        for (int i = 0; i < safetyCap; i++) {
            List<Long> ids = resultRepo.findReanalysisTargetIds(since, watermark, PageRequest.of(0, chunkSize));
            if (ids.isEmpty()) break;

            // 청크 선삭제 — feedbacks ON DELETE CASCADE(현재 0건 무손실), 캐시는 TTL 자연 만료
            // self-invocation 프록시 우회 방지를 위해 TransactionTemplate 직접 사용
            final List<Long> toDelete = ids;
            txTemplate.execute(status -> { resultRepo.deleteByDisclosureIdIn(toDelete); return null; });

            int analyzed = 0, failed = 0;
            for (Long id : ids) {
                try {
                    Optional<?> res = stage2Analyzer.analyze(id);
                    if (res.isPresent()) analyzed++;
                    else failed++;
                } catch (Exception e) {
                    log.warn("ReanalysisService: 재분석 실패 id={} err={}", id, e.getMessage());
                    failed++;
                }
            }
            analyzedCum += analyzed;
            failedCum += failed;
            watermark = ids.get(ids.size() - 1) + 1;
            recordProgress(jobId, analyzed, failed);
            log.info("ReanalysisService: chunk done analyzed={}/{} failed={} watermark={}",
                    analyzedCum, total, failedCum, watermark);

            if (failedCum > 50 && analyzedCum == 0) {
                throw new IllegalStateException(
                        "ReanalysisService 조기 중단: 50건 이상 시도 후 성공 0건. LLM 가용성 확인 필요.");
            }
        }

        succeedJob(jobId);
        log.info("ReanalysisService: done jobId={} analyzed={} failed={}", jobId, analyzedCum, failedCum);
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
    public void recordProgress(UUID jobId, int analyzedDelta, int failedDelta) {
        jobRepo.findByJobId(jobId).ifPresent(j -> { j.recordChunkProgress(analyzedDelta, failedDelta); jobRepo.save(j); });
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
