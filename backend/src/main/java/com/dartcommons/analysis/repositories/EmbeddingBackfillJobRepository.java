package com.dartcommons.analysis.repositories;

import com.dartcommons.analysis.entities.EmbeddingBackfillJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/*
 * [목적] EmbeddingBackfillJob CRUD — jobId UUID로 진행률 조회/상태 갱신, stale RUNNING 잡 탐지.
 * [이유] 비동기 임베딩 백필 잡 상태 영속화. 재시작 후 status=RUNNING 잡을 찾아 last_processed_id에서 재개.
 * [사이드 임팩트] findFirstByStatusOrderByCreatedAtDesc: 재시작 재개에서 가장 최근 RUNNING 잡 1건만 탐색.
 *               잡 갱신은 청크당 1회 — EmbeddingBackfillJobStateService의 REQUIRES_NEW 트랜잭션으로 커밋.
 * [수정 시 고려사항] 잡 정리는 별도 cleanup 잡 후속.
 *                  다중 인스턴스 환경에서 findFirstByStatus 결과가 다른 인스턴스의 정상 RUNNING 잡일 수 있음.
 */
public interface EmbeddingBackfillJobRepository extends JpaRepository<EmbeddingBackfillJob, Long> {

    Optional<EmbeddingBackfillJob> findByJobId(UUID jobId);

    /** 가장 최근 생성된 RUNNING 잡 조회 — 재시작 후 stale 잡 탐지 및 재개에 사용. */
    Optional<EmbeddingBackfillJob> findFirstByStatusOrderByCreatedAtDesc(EmbeddingBackfillJob.Status status);
}
