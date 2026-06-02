package com.dartcommons.disclosure.services;

import com.dartcommons.disclosure.entities.BackfillJob;
import com.dartcommons.disclosure.repositories.BackfillJobRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/*
 * [목적] 비동기 백필 잡 오케스트레이션 — 잡 생성·실행·진행률 갱신·완료/실패 기록.
 * [이유] 동기 백필(BackfillService.backfill)은 3년치 = 시간 단위 호출 → HTTP 타임아웃 위험.
 *       컨트롤러는 잡 생성 후 즉시 202 + jobId 반환, 백필은 @Async 스레드에서 실행.
 * [사이드 임팩트] @Async는 별도 스레드 풀 사용 — TaskExecutor 미설정 시 Spring 기본(SimpleAsyncTaskExecutor).
 *               운영 부하 클 경우 ThreadPoolTaskExecutor로 풀 제한 권장.
 *               청크 1개당 1회 UPDATE — 13개 청크면 13회 UPDATE. 인덱스 영향 미미.
 *               same-class self-invocation은 @Async 작동 안 함 — Controller가 호출하는 방식 유지.
 * [수정 시 고려사항] 진행률 갱신은 PROPAGATION.REQUIRES_NEW로 분리 — 잡 자체 트랜잭션과 독립.
 *                  진행 중 잡 cleanup, 재시작 정책은 후속 Spec.
 *                  ChunkProgressListener는 호출 측에서 예외를 잡으므로 본 서비스의 트랜잭션 실패는 잡 자체에 영향 안 줌.
 */
@Service
@RequiredArgsConstructor
public class BackfillJobService {

    private static final Logger log = LoggerFactory.getLogger(BackfillJobService.class);

    private final BackfillJobRepository jobRepository;
    private final DisclosureBackfillService backfillService;

    /**
     * 잡 생성 + 즉시 RUNNING 전이가 아닌 PENDING으로 저장. 호출자(컨트롤러)는 jobId를 반환받아
     * 클라이언트에 즉시 응답하고, 별도 @Async 메서드로 실제 백필을 트리거.
     */
    @Transactional
    public BackfillJob createJob(LocalDate from, LocalDate to, boolean emitEvents) {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from(" + from + ") must be <= to(" + to + ")");
        }
        BackfillJob job = jobRepository.save(BackfillJob.create(from, to, emitEvents));
        log.info("Backfill job created: jobId={}, from={}, to={}", job.getJobId(), from, to);
        return job;
    }

    /**
     * 별도 스레드에서 백필 실행. 호출자(컨트롤러)는 즉시 반환됨.
     * 청크 완료마다 별도 트랜잭션으로 잡 진행률 갱신(REQUIRES_NEW).
     */
    @Async
    public void runAsync(UUID jobId) {
        BackfillJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null) {
            log.error("Backfill job not found for async run: jobId={}", jobId);
            return;
        }

        int chunksTotal = DisclosureBackfillService.calculateChunks(job.getFromDate(), job.getToDate());
        startJob(jobId, chunksTotal);

        try {
            backfillService.backfill(
                    job.getFromDate(), job.getToDate(), job.isEmitEvents(),
                    (fetched, saved) -> recordProgress(jobId, fetched, saved));
            succeedJob(jobId);
            log.info("Backfill job done: jobId={}", jobId);
        } catch (Exception e) {
            failJob(jobId, e.getMessage());
            log.error("Backfill job failed: jobId={}", jobId, e);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startJob(UUID jobId, int chunksTotal) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.start(chunksTotal);
            jobRepository.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProgress(UUID jobId, int fetched, int saved) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.recordChunkProgress(fetched, saved);
            jobRepository.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedJob(UUID jobId) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.succeed();
            jobRepository.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(UUID jobId, String error) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.fail(error);
            jobRepository.save(j);
        });
    }

    @Transactional(readOnly = true)
    public Optional<BackfillJob> findByJobId(UUID jobId) {
        return jobRepository.findByJobId(jobId);
    }
}
