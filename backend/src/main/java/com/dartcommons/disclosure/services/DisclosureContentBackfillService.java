package com.dartcommons.disclosure.services;

import com.dartcommons.disclosure.entities.ContentBackfillJob;
import com.dartcommons.disclosure.repositories.ContentBackfillJobRepository;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.dart.DartApiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * [목적] content_fetched_at IS NULL인 공시를 커서 기반 청크로 순회하며 본문 fetch 백필을 수행.
 *       잡(ContentBackfillJob) 단위로 진행률·last_processed_id를 DB 영속화해 재시작 복구 지원.
 * [이유] content-fetch-backfill-resilience Spec:
 *       (1) 이전 구현은 AtomicBoolean + 로그만 — 재시작 시 12h+ 진행률 유실.
 *       (2) AnalysisBackfillService(V13/analysis 도메인)의 검증된 잡 추적 패턴 채택.
 *       (3) 재시작 복구: 시작 시 status=RUNNING 잡이 있으면 stale(크래시) 판정 → last_processed_id 재개.
 *       AtomicBoolean은 JVM 내 중복 실행 방지용으로 유지(잡 테이블은 진행률/재개 전담 — 역할 분리).
 *       REQUIRES_NEW self-invocation 방지: 잡 상태 전이는 ContentBackfillJobStateService 위임.
 * [사이드 임팩트] ContentBackfillJob 행은 영구 보존(감사). 잡 갱신은 청크당 1회 REQUIRES_NEW 커밋.
 *               createAndStartAsync(): CAS → createJob → executor.execute(doBackfill) 원자 메서드.
 *               running=false(JVM재시작) ∧ DB status=RUNNING → stale 판정 → last_processed_id부터 재개.
 *               다중 인스턴스(Kubernetes) 환경에서는 stale 판정이 정상 RUNNING 잡을 오인할 수 있음.
 *               content-backfill-distributed-lock Spec이 이 갭을 메운다(분산 락으로 교체).
 * [수정 시 고려사항] contentBackfillChunkSize: DART 일일 호출 한도 실측 후 조정(기본 100 보수적).
 *                  safety cap: (initialEstimated/chunkSize+2)*2 — estimated=0이면 cap=4(방어 최솟값).
 *                  자동 스케줄(야간 배치) 필요 시 @Scheduled + createAndStartAsync() 조합으로 추가.
 *                  단일 인스턴스 배포 불변식 유지 필수 — 수평 확장 전 분산 락 Spec 먼저 구현.
 *                  LOW-4(미적용): startJob/recordProgress 내 findByJobId+save를 ID UPDATE 쿼리로 교체 시
 *                  조회 1회 절감 가능 — 현재 규모에서 불필요, 성능 병목 시 후속 최적화.
 */
@Service
public class DisclosureContentBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DisclosureContentBackfillService.class);

    // JVM 단일 인스턴스 중복 실행 방지 — 잡 테이블의 RUNNING 상태와 이중 보호
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final ContentBackfillJobStateService stateService;
    private final ContentBackfillJobRepository jobRepository;
    private final DisclosureRepository disclosureRepository;
    private final DisclosureContentService disclosureContentService;
    private final DartApiProperties props;
    private final TaskExecutor contentFetchExecutor;

    public DisclosureContentBackfillService(ContentBackfillJobStateService stateService,
                                             ContentBackfillJobRepository jobRepository,
                                             DisclosureRepository disclosureRepository,
                                             DisclosureContentService disclosureContentService,
                                             DartApiProperties props,
                                             @Qualifier("contentFetchExecutor") TaskExecutor contentFetchExecutor) {
        this.stateService = stateService;
        this.jobRepository = jobRepository;
        this.disclosureRepository = disclosureRepository;
        this.disclosureContentService = disclosureContentService;
        this.props = props;
        this.contentFetchExecutor = contentFetchExecutor;
    }

    /**
     * 원자적 잡 생성 + 비동기 실행 시작 (TOCTOU 해소).
     *
     * <p>CAS 확보 → createJob → executor.execute(doBackfill) 순서로 단일 메서드에서 처리.
     * isRunning() 확인과 createJob 사이의 race condition 없음.
     * 이미 실행 중이면 {@link Optional#empty()} 반환 — 호출자가 409 등 응답 책임.
     */
    public Optional<ContentBackfillJob> createAndStartAsync() {
        if (!running.compareAndSet(false, true)) {
            return Optional.empty();
        }
        try {
            ContentBackfillJob job = createJob();
            UUID jobId = job.getJobId();
            // executor.execute: @Async self-invocation 없이 같은 contentFetchExecutor 풀에 submit
            contentFetchExecutor.execute(() -> {
                try {
                    doBackfill(jobId);
                } catch (Exception e) {
                    stateService.failJob(jobId, e.getMessage());
                    log.error("DisclosureContentBackfillService: doBackfill failed jobId={}", jobId, e);
                } finally {
                    running.set(false);
                }
            });
            return Optional.of(job);
        } catch (Exception e) {
            running.set(false); // createJob 실패 시 CAS 복원
            throw e;
        }
    }

    /**
     * 비동기 백필 실행 — @Async 직접 호출용 (단위 테스트 · 재시도).
     * 컨트롤러에서는 {@link #createAndStartAsync()} 사용 권장.
     */
    @Async("contentFetchExecutor")
    public void runAsync(UUID jobId) {
        if (!running.compareAndSet(false, true)) {
            log.warn("DisclosureContentBackfillService: already running, skip jobId={}", jobId);
            return;
        }
        try {
            doBackfill(jobId);
        } catch (Exception e) {
            stateService.failJob(jobId, e.getMessage());
            log.error("DisclosureContentBackfillService: job failed jobId={}", jobId, e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 백필 핵심 루프 — createAndStartAsync와 runAsync에서 공유.
     *
     * <p>재시작 복구: 시작 시 status=RUNNING 잡 탐지 → stale(크래시) 판정 → last_processed_id 재개.
     * <p>커서 패턴: lastId=null(또는 재개 시 stale.lastProcessedId)에서 시작, 청크마다 ids.getLast() 전진.
     * <p>safety cap: {@code (initialEstimated/chunkSize+2)*2} — 무한루프 방지.
     */
    private void doBackfill(UUID jobId) {
        ContentBackfillJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null) {
            // createJob 직후 호출이므로 정상 흐름에서 발생 불가 — 발생 시 버그 신호
            log.error("DisclosureContentBackfillService: job not found jobId={} — possible bug in call sequence", jobId);
            return;
        }

        Long resumeFromId = resolveResumePoint(jobId);
        long initialEstimated = disclosureRepository.countPendingContentFetch();
        int chunkSize = props.contentBackfillChunkSize();
        int safetyCap = (int) ((initialEstimated / Math.max(chunkSize, 1) + 2) * 2);

        stateService.startJob(jobId, (int) initialEstimated);
        log.info("DisclosureContentBackfillService: start jobId={} initialEstimated={} chunkSize={} safetyCap={} resumeFrom={}",
                jobId, initialEstimated, chunkSize, safetyCap, resumeFromId);

        Long lastId = resumeFromId;
        int processed = 0;
        int failed = 0;

        for (int i = 0; i < safetyCap; i++) {
            List<Long> ids = disclosureRepository.findPendingContentFetchIds(
                    lastId, PageRequest.of(0, chunkSize));
            if (ids.isEmpty()) break;

            int chunkProcessed = 0;
            int chunkFailed = 0;
            for (Long id : ids) {
                try {
                    disclosureContentService.fetchAndSave(id);
                    chunkProcessed++;
                } catch (Exception e) {
                    log.warn("DisclosureContentBackfillService: fetch failed id={} reason={}", id, e.getMessage());
                    chunkFailed++;
                }

                if (props.contentBackfillThrottleMs() > 0) {
                    try {
                        Thread.sleep(props.contentBackfillThrottleMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.warn("DisclosureContentBackfillService: interrupted at {}/{} lastId={}",
                                processed + chunkProcessed, initialEstimated, lastId);
                        stateService.recordProgress(jobId, chunkProcessed, chunkFailed, ids.getLast());
                        stateService.failJob(jobId, "Interrupted");
                        return;
                    }
                }
            }

            processed += chunkProcessed;
            failed += chunkFailed;
            lastId = ids.getLast();
            stateService.recordProgress(jobId, chunkProcessed, chunkFailed, lastId);
            log.info("DisclosureContentBackfillService: chunk done processed={}/{} failed={} lastId={}",
                    processed, initialEstimated, failed, lastId);
        }

        stateService.succeedJob(jobId);
        log.info("DisclosureContentBackfillService: done jobId={} processed={} failed={}", jobId, processed, failed);
    }

    /** 재시작 재개 포인트 결정 — stale RUNNING 잡이 있으면 last_processed_id 반환, 없으면 null(처음부터). */
    private Long resolveResumePoint(UUID currentJobId) {
        Optional<ContentBackfillJob> stale = jobRepository
                .findFirstByStatusOrderByCreatedAtDesc(ContentBackfillJob.Status.RUNNING);
        if (stale.isPresent() && !stale.get().getJobId().equals(currentJobId)) {
            Long resumeId = stale.get().getLastProcessedId();
            log.info("DisclosureContentBackfillService: stale RUNNING job detected (jobId={}) resuming from lastProcessedId={}",
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
    public Optional<ContentBackfillJob> findByJobId(UUID jobId) {
        return jobRepository.findByJobId(jobId);
    }

    /** 신규 잡 PENDING 상태로 저장. 호출자가 createAndStartAsync() 또는 runAsync()를 이어 호출한다. */
    @Transactional
    public ContentBackfillJob createJob() {
        ContentBackfillJob job = jobRepository.save(ContentBackfillJob.create());
        log.info("ContentBackfillJob created: jobId={}", job.getJobId());
        return job;
    }

    // --- 위임 메서드 (ContentBackfillJobStateService 프록시 경유 — REQUIRES_NEW 보장) ---
    // IT 테스트(ContentBackfillJobIT)가 서비스 레벨 인터페이스를 통해 직접 호출 가능하도록 유지.

    public void startJob(UUID jobId, int targeted) { stateService.startJob(jobId, targeted); }

    public void recordProgress(UUID jobId, int processedDelta, int failedDelta, Long lastId) {
        stateService.recordProgress(jobId, processedDelta, failedDelta, lastId);
    }

    public void succeedJob(UUID jobId) { stateService.succeedJob(jobId); }

    public void failJob(UUID jobId, String error) { stateService.failJob(jobId, error); }
}
