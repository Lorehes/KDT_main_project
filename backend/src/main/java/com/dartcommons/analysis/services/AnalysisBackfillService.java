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

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/*
 * [목적] 미분석 공시 91k 전건에 Stage 2를 일괄 적용하는 비동기 백필 잡 오케스트레이션.
 *       잡 생성·청크 처리·진행률 갱신·완료/실패 기록. 분석 자체는 Stage2Analyzer 위임.
 * [이유] 신규 공시는 폴링 이벤트로 자동 분석되지만 이전 적재분 91k건은 별도 백필 필요.
 *       analysisBackfillExecutor 풀(core 1/max 2)로 폴링 풀과 격리 — 폴링 SLO 보호.
 * [사이드 임팩트] 잡 1회당 청크 N개 × Stage2Analyzer 호출 × 청크 크기(기본 100). 청크당 1회 UPDATE.
 *               Stage2Analyzer가 idempotent라 동일 잡 재실행 시 분석 결과 중복 없음.
 *               LLM 실패 disclosure는 stage_reached=1 유지 — 다음 백필이 재시도 가능.
 * [수정 시 고려사항] 청크 크기는 LLM RPS 한계 — 100 적정. 200 이상은 Ollama 큐 적체 위험.
 *                  진행 중 잡 cleanup, 재시작 정책은 후속.
 *                  대량 처리 중 OOM 위험 — 청크 처리 후 결과 list 참조 해제.
 */
@Service
public class AnalysisBackfillService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisBackfillService.class);

    private final AnalysisJobRepository jobRepo;
    private final AnalysisResultRepository resultRepo;
    private final Stage2Analyzer stage2Analyzer;

    public AnalysisBackfillService(AnalysisJobRepository jobRepo,
                                   AnalysisResultRepository resultRepo,
                                   Stage2Analyzer stage2Analyzer) {
        this.jobRepo = jobRepo;
        this.resultRepo = resultRepo;
        this.stage2Analyzer = stage2Analyzer;
    }

    @Transactional
    public AnalysisJob createJob(Long idFrom, Long idTo, int chunkSize) {
        if (idFrom != null && idTo != null && idFrom > idTo) {
            throw new IllegalArgumentException("idFrom(" + idFrom + ") must be <= idTo(" + idTo + ")");
        }
        if (chunkSize <= 0 || chunkSize > 500) {
            throw new IllegalArgumentException("chunkSize must be 1~500 (recommended 100)");
        }
        AnalysisJob job = jobRepo.save(AnalysisJob.create(AnalysisStage.LLM_CLASSIFY, idFrom, idTo, chunkSize));
        log.info("Analysis backfill job created: jobId={}, stage=2, idFrom={}, idTo={}, chunkSize={}",
                job.getJobId(), idFrom, idTo, chunkSize);
        return job;
    }

    /**
     * 백필 비동기 실행 — analysisBackfillExecutor 풀에서 동작(폴링 풀과 격리).
     * 청크 단위 처리: 미분석 공시 id를 chunkSize만큼 가져와 분석 후 진행률 UPDATE.
     */
    @Async("analysisBackfillExecutor")
    public void runAsync(UUID jobId) {
        AnalysisJob job = jobRepo.findByJobId(jobId).orElse(null);
        if (job == null) {
            log.error("Analysis backfill job not found: jobId={}", jobId);
            return;
        }

        long total = resultRepo.countUnanalyzedDisclosures(job.getDisclosureIdFrom(), job.getDisclosureIdTo());
        int chunkSize = job.getChunkSize();
        int chunksTotal = (int) Math.ceil(total / (double) chunkSize);
        startJob(jobId, chunksTotal, (int) total);
        log.info("Analysis backfill start: jobId={} targeted={} chunks={}", jobId, total, chunksTotal);

        try {
            int analyzedCum = 0;
            int failedCum = 0;
            int safetyCap = (chunksTotal + 1) * 2; // 무한 루프 방지: 예상 청크 수의 2배 상한
            // 워터마크: 청크 처리 후 마지막 id 다음부터 다시 조회 — LLM 영구 실패한 disclosure로 인한 무한 루프 차단.
            Long watermark = job.getDisclosureIdFrom();
            for (int processed = 0; processed < safetyCap; processed++) {
                List<Long> ids = resultRepo.findUnanalyzedDisclosureIds(
                        watermark, job.getDisclosureIdTo(),
                        PageRequest.of(0, chunkSize));
                if (ids.isEmpty()) break;

                int analyzed = 0;
                int failed = 0;
                for (Long id : ids) {
                    try {
                        Optional<?> res = stage2Analyzer.analyze(id);
                        if (res.isPresent()) analyzed++;
                        else failed++;
                    } catch (Exception e) {
                        log.warn("Analysis backfill: 청크 내 1건 예외 — id={} err={}", id, e.getMessage());
                        failed++;
                    }
                }
                analyzedCum += analyzed;
                failedCum += failed;
                recordProgress(jobId, analyzed, failed);
                // 다음 청크는 이번 청크의 마지막 id+1 부터 — 실패한 disclosure가 다시 조회되지 않도록.
                watermark = ids.get(ids.size() - 1) + 1;
                log.debug("Analysis backfill chunk done: jobId={} ids={}~{} analyzed={} failed={} cum={}/{} watermark={}",
                        jobId, ids.get(0), ids.get(ids.size() - 1), analyzed, failed,
                        analyzedCum, total, watermark);
                // 청크가 모두 실패 + 전체 청크 누적 실패율 ≥80%면 즉시 중단 (안전망)
                if (failedCum > 50 && analyzedCum == 0) {
                    throw new IllegalStateException(
                            "Analysis backfill 조기 중단: 50건 이상 시도 후 분석 성공 0건. " +
                            "LLM provider 설정 또는 Ollama 가용성 확인 필요.");
                }
            }
            succeedJob(jobId);
            log.info("Analysis backfill job done: jobId={} analyzed={} failed={}",
                    jobId, analyzedCum, failedCum);
        } catch (Exception e) {
            failJob(jobId, e.getMessage());
            log.error("Analysis backfill job failed: jobId={}", jobId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startJob(UUID jobId, int chunksTotal, int targeted) {
        jobRepo.findByJobId(jobId).ifPresent(j -> {
            j.start(chunksTotal, targeted);
            jobRepo.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProgress(UUID jobId, int analyzedDelta, int failedDelta) {
        jobRepo.findByJobId(jobId).ifPresent(j -> {
            j.recordChunkProgress(analyzedDelta, failedDelta);
            jobRepo.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedJob(UUID jobId) {
        jobRepo.findByJobId(jobId).ifPresent(j -> {
            j.succeed();
            jobRepo.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(UUID jobId, String error) {
        jobRepo.findByJobId(jobId).ifPresent(j -> {
            j.fail(error);
            jobRepo.save(j);
        });
    }

    @Transactional(readOnly = true)
    public Optional<AnalysisJob> findByJobId(UUID jobId) {
        return jobRepo.findByJobId(jobId);
    }
}
