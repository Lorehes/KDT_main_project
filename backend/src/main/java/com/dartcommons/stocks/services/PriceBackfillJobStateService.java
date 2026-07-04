package com.dartcommons.stocks.services;

import com.dartcommons.stocks.repositories.PriceBackfillJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.UUID;

/*
 * [목적] PriceBackfillJob 상태 전이(start/recordProgress/succeed/fail)를 REQUIRES_NEW로 격리 커밋 —
 *       백필 루프의 날짜 처리마다 진행률을 즉시 DB 반영해 재개 포인트 최신 유지.
 * [이유] Spring @Transactional(REQUIRES_NEW)는 AOP 프록시 경유만 동작 — self-invocation 무효.
 *       별도 빈으로 분리해 PriceBackfillService가 프록시를 경유하도록 강제(V26 EmbeddingBackfillJobStateService 동일).
 * [사이드 임팩트] 상태 쓰기 전담 — 읽기(findByJobId)는 호출자 책임.
 * [수정 시 고려사항] 다중 인스턴스 시 낙관적 락(@Version) 또는 분산 락 필요.
 */
@Service
public class PriceBackfillJobStateService {

    private final PriceBackfillJobRepository jobRepository;

    public PriceBackfillJobStateService(PriceBackfillJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void startJob(UUID jobId, int targeted) {
        jobRepository.findByJobId(jobId).ifPresent(j -> { j.start(targeted); jobRepository.save(j); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordProgress(UUID jobId, int processedDelta, int failedDelta, LocalDate lastDate) {
        jobRepository.findByJobId(jobId).ifPresent(j -> {
            j.recordProgress(processedDelta, failedDelta, lastDate);
            jobRepository.save(j);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void succeedJob(UUID jobId) {
        jobRepository.findByJobId(jobId).ifPresent(j -> { j.succeed(); jobRepository.save(j); });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failJob(UUID jobId, String error) {
        jobRepository.findByJobId(jobId).ifPresent(j -> { j.fail(error); jobRepository.save(j); });
    }

    /*
     * [목적] 안전망 도달 시 datesOk>0이면 PARTIAL로 원자 커밋 — 진행 중인 루프 트랜잭션과 격리.
     * [이유] REQUIRES_NEW 없이 self-invocation하면 트랜잭션 전파가 무효 — failJob와 동일 패턴.
     * [사이드 임팩트] reason은 errorMessage에 저장(PriceBackfillJob.partial()이 1000자 절삭).
     * [수정 시 고려사항] PriceBackfillService.doBackfill의 PARTIAL 분기에서만 호출.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void partialJob(UUID jobId, String reason) {
        jobRepository.findByJobId(jobId).ifPresent(j -> { j.partial(reason); jobRepository.save(j); });
    }
}
