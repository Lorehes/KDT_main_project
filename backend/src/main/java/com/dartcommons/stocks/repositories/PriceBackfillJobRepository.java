package com.dartcommons.stocks.repositories;

import com.dartcommons.stocks.entities.PriceBackfillJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/*
 * [목적] PriceBackfillJob 조회 — jobId 단건 + stale RUNNING 재개 탐색.
 * [이유] V26 EmbeddingBackfillJobRepository 동일 패턴. 상태 쓰기는 PriceBackfillJobStateService(REQUIRES_NEW).
 * [사이드 임팩트] findFirstByStatusOrderByCreatedAtDesc: 재시작 시 가장 최근 RUNNING 1건만 탐색(단일 인스턴스 전제).
 * [수정 시 고려사항] 다중 인스턴스 시 findFirstByStatus가 다른 인스턴스의 정상 RUNNING을 오인 가능.
 */
public interface PriceBackfillJobRepository extends JpaRepository<PriceBackfillJob, Long> {

    Optional<PriceBackfillJob> findByJobId(UUID jobId);

    Optional<PriceBackfillJob> findFirstByStatusOrderByCreatedAtDesc(PriceBackfillJob.Status status);
}
