package com.dartcommons.analysis.services;

import com.dartcommons.analysis.repositories.EmbeddingBackfillJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/*
 * [목적] EmbeddingBackfillJob 상태 전이(start/recordProgress/succeed/fail)를 REQUIRES_NEW 트랜잭션으로 격리 커밋.
 *       백필 루프의 각 청크 완료마다 진행률을 즉시 DB에 반영해 재시작 복구 포인트를 최신으로 유지.
 * [이유] Spring @Transactional(REQUIRES_NEW)는 AOP 프록시를 통해서만 동작 — self-invocation 불가.
 *       EmbeddingBackfillService 내에서 직접 호출(this.startJob())하면 REQUIRES_NEW가 무시됨.
 *       별도 빈으로 분리해 EmbeddingBackfillService에서 프록시를 경유하도록 강제(V25 ContentBackfillJobStateService 동일 패턴).
 * [사이드 임팩트] 이 빈은 EmbeddingBackfillJob 상태 쓰기에만 집중 — 읽기(findByJobId)는 호출자 책임.
 *               REQUIRES_NEW로 외부 트랜잭션 없어도 항상 독립 트랜잭션 시작 → 즉시 커밋 보장.
 * [수정 시 고려사항] findByJobId + save 쌍은 단일 인스턴스 환경(AtomicBoolean 보장)에서 원자적으로 동작.
 *                  다중 인스턴스 환경(Kubernetes)에서는 낙관적 락(@Version) 또는 분산 락이 필요.
 */
@Service
public class EmbeddingBackfillJobStateService {

    private final EmbeddingBackfillJobRepository jobRepository;

    public EmbeddingBackfillJobStateService(EmbeddingBackfillJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startJob(UUID jobId, int targeted) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.start(targeted);
            jobRepository.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProgress(UUID jobId, int processedDelta, int failedDelta, Long lastId) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.recordChunkProgress(processedDelta, failedDelta, lastId);
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
}
