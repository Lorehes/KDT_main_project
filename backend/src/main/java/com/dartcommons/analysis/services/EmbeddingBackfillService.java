package com.dartcommons.analysis.services;

import com.dartcommons.analysis.entities.EmbeddingBackfillJob;
import com.dartcommons.analysis.repositories.EmbeddingBackfillJobRepository;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * [목적] content_text 보유 공시 전건을 Chroma에 일괄 임베딩하는 비동기 백필 잡 오케스트레이션.
 *       잡 생성·청크 처리·진행률 갱신·완료/실패 기록. 임베딩 자체는 Stage3RagService.upsert() 위임.
 * [이유] Stage 3 RAG 코퍼스(disclosure_embeddings) 현재 0건 — 93k 코퍼스 미적재로 findSimilar() 무동작.
 *       증분(폴링 시 자동 upsert)만으로는 과거 공시가 영구 누락.
 *       analysisBackfillExecutor 풀(core1/max2) 재사용 — 신규 풀 없이 폴링 풀과 격리.
 * [사이드 임팩트] 백필 ~2h 동안 analysisBackfillExecutor 점유. 동시간 신규 공시 분석 백필 큐 대기 가능.
 *               건별로 Stage3RagService.upsert()가 Ollama 임베딩 + Chroma upsert 호출 — Ollama 단일 인스턴스 공유.
 *               AtomicBoolean: JVM 단일 인스턴스 중복 실행 방지. EmbeddingBackfillJob 테이블: 진행률/재개 담당.
 *               DisclosureRepository 직접 주입: analysis→disclosure read-only 의존. Stage3RagService가
 *               이미 동일 패턴을 성립시킨 기존 관례(Spec 명시 수용) — facade indirection 회피.
 * [수정 시 고려사항] CHUNK_SIZE=100: 청크당 DB 갱신 빈도 조절용 — 임베딩 병렬성과 무관(Ollama 직렬).
 *                  안전망(EARLY_ABORT_THRESHOLD 시도 후 성공 0건 → throw): Ollama/Chroma 미기동 시 조기 중단.
 *                  단일 인스턴스 배포 불변식 유지 필수 — 수평 확장 전 분산 락 Spec 먼저 구현.
 *                  저부하 시간대 실행 권장 — Ollama 임베딩 점유로 증분 임베딩 지연 가능.
 */
@Service
public class EmbeddingBackfillService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingBackfillService.class);
    private static final int CHUNK_SIZE = 100;
    // 조기 중단 안전망 임계 — 이만큼 시도 후 성공 0건이면 Ollama/Chroma 미기동으로 판정하고 throw.
    private static final int EARLY_ABORT_THRESHOLD = 50;

    // JVM 단일 인스턴스 중복 실행 방지 — 잡 테이블의 RUNNING 상태와 이중 보호
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final EmbeddingBackfillJobStateService stateService;
    private final EmbeddingBackfillJobRepository jobRepository;
    private final DisclosureRepository disclosureRepository;
    private final Stage3RagService stage3RagService;
    private final TaskExecutor analysisBackfillExecutor;

    public EmbeddingBackfillService(EmbeddingBackfillJobStateService stateService,
                                    EmbeddingBackfillJobRepository jobRepository,
                                    DisclosureRepository disclosureRepository,
                                    Stage3RagService stage3RagService,
                                    @Qualifier("analysisBackfillExecutor") TaskExecutor analysisBackfillExecutor) {
        this.stateService = stateService;
        this.jobRepository = jobRepository;
        this.disclosureRepository = disclosureRepository;
        this.stage3RagService = stage3RagService;
        this.analysisBackfillExecutor = analysisBackfillExecutor;
    }

    /**
     * 원자적 잡 생성 + 비동기 실행 시작 (TOCTOU 해소).
     * CAS 확보 → createJob → executor.execute(doBackfill) 순서로 단일 메서드에서 처리.
     * 이미 실행 중이면 {@link Optional#empty()} 반환 — 호출자가 409 응답 책임.
     */
    public Optional<EmbeddingBackfillJob> createAndStartAsync() {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            EmbeddingBackfillJob job = createJob();
            UUID jobId = job.getJobId();
            analysisBackfillExecutor.execute(() -> {
                try {
                    doBackfill(jobId);
                } catch (Exception e) {
                    stateService.failJob(jobId, e.getMessage());
                    log.error("EmbeddingBackfillService: doBackfill failed jobId={}", jobId, e);
                } finally {
                    running.set(false);
                }
            });
            return Optional.of(job);
        } catch (Exception e) {
            running.set(false);
            throw e;
        }
    }

    /**
     * 백필 핵심 루프.
     * 재시작 복구: 시작 시 status=RUNNING 잡 탐지 → stale(크래시) 판정 → last_processed_id 재개.
     * 커서 패턴: lastId=null에서 시작, 청크마다 ids.getLast() 전진 — 실패 건도 커서 전진(무한루프 차단).
     * safety cap: (initialEstimated/CHUNK_SIZE+2)*2 — 예상 청크 수의 2배 상한.
     */
    private void doBackfill(UUID jobId) {
        EmbeddingBackfillJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null) {
            log.error("EmbeddingBackfillService: job not found jobId={} — possible bug in call sequence", jobId);
            return;
        }

        Long resumeFromId = resolveResumePoint(jobId);
        // ponytail: 1회성 관리자 백필의 시작 시점 1회 COUNT — full scan 수용.
        // 진행률 표시/safetyCap용 근사치라 정밀도 불필요. 대규모화 시 pg_class.reltuples 추정으로 승급.
        long initialEstimated = disclosureRepository.countWithContentText();
        int safetyCap = (int) ((initialEstimated / Math.max(CHUNK_SIZE, 1) + 2) * 2);

        stateService.startJob(jobId, (int) initialEstimated);
        log.info("EmbeddingBackfillService: start jobId={} initialEstimated={} safetyCap={} resumeFrom={}",
                jobId, initialEstimated, safetyCap, resumeFromId);

        Long lastId = resumeFromId;
        int processed = 0;
        int failed = 0;

        for (int i = 0; i < safetyCap; i++) {
            List<Long> ids = disclosureRepository.findIdsWithContentText(lastId, PageRequest.of(0, CHUNK_SIZE));
            if (ids.isEmpty()) break;

            int chunkProcessed = 0;
            int chunkFailed = 0;
            for (Long id : ids) {
                try {
                    stage3RagService.upsert(id);
                    chunkProcessed++;
                } catch (Exception e) {
                    log.warn("EmbeddingBackfillService: upsert failed id={} err={}", id, e.getMessage());
                    chunkFailed++;
                }
            }

            processed += chunkProcessed;
            failed += chunkFailed;
            lastId = ids.getLast();
            stateService.recordProgress(jobId, chunkProcessed, chunkFailed, lastId);
            log.info("EmbeddingBackfillService: chunk done processed={}/{} failed={} lastId={}",
                    processed, initialEstimated, failed, lastId);

            // 임계 초과 시도 후 성공 0건 → Ollama/Chroma 미기동 가능성 → 조기 중단(안전망)
            if (failed > EARLY_ABORT_THRESHOLD && processed == 0) {
                throw new IllegalStateException(
                        "EmbeddingBackfill 조기 중단: 50건 이상 시도 후 성공 0건. " +
                        "EMBEDDING_PROVIDER=ollama + Ollama 기동 여부 및 CHROMA_ENABLED=true + Chroma 기동 여부 확인 필요.");
            }
        }

        stateService.succeedJob(jobId);
        log.info("EmbeddingBackfillService: done jobId={} processed={} failed={}", jobId, processed, failed);
    }

    /** 재시작 재개 포인트 결정 — stale RUNNING 잡이 있으면 last_processed_id 반환, 없으면 null(처음부터). */
    private Long resolveResumePoint(UUID currentJobId) {
        Optional<EmbeddingBackfillJob> stale = jobRepository
                .findFirstByStatusOrderByCreatedAtDesc(EmbeddingBackfillJob.Status.RUNNING);
        if (stale.isPresent() && !stale.get().getJobId().equals(currentJobId)) {
            Long resumeId = stale.get().getLastProcessedId();
            log.info("EmbeddingBackfillService: stale RUNNING job detected (jobId={}) resuming from lastProcessedId={}",
                    stale.get().getJobId(), resumeId);
            stateService.failJob(stale.get().getJobId(), "Superseded by new job " + currentJobId + " on restart");
            return resumeId;
        }
        return null;
    }

    public boolean isRunning() {
        return running.get();
    }

    @Transactional(readOnly = true)
    public Optional<EmbeddingBackfillJob> findByJobId(UUID jobId) {
        return jobRepository.findByJobId(jobId);
    }

    @Transactional
    public EmbeddingBackfillJob createJob() {
        EmbeddingBackfillJob job = jobRepository.save(EmbeddingBackfillJob.create());
        log.info("EmbeddingBackfillJob created: jobId={}", job.getJobId());
        return job;
    }

    // --- 위임 메서드 (EmbeddingBackfillJobStateService 프록시 경유 — REQUIRES_NEW 보장) ---
    // IT 테스트(EmbeddingBackfillJobIT)가 서비스 레벨 인터페이스를 통해 직접 호출 가능하도록 유지.

    public void startJob(UUID jobId, int targeted) { stateService.startJob(jobId, targeted); }

    public void recordProgress(UUID jobId, int processedDelta, int failedDelta, Long lastId) {
        stateService.recordProgress(jobId, processedDelta, failedDelta, lastId);
    }

    public void succeedJob(UUID jobId) { stateService.succeedJob(jobId); }

    public void failJob(UUID jobId, String error) { stateService.failJob(jobId, error); }
}
