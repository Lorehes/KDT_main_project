package com.dartcommons.analysis.repositories;

import com.dartcommons.analysis.entities.AnalysisJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/*
 * [목적] AnalysisJob CRUD — jobId UUID로 진행률 조회/상태 갱신.
 * [이유] 비동기 백필 잡 상태 영속화. 컨트롤러는 jobId만 알면 진행률 조회 가능.
 * [사이드 임팩트] 잡 갱신은 청크당 1회 발생 — 비용 낮으나 빈번한 flush 주의(@Async 트랜잭션 분리).
 * [수정 시 고려사항] 잡 정리(soft delete)는 본 Spec 범위 밖 — 별도 cleanup 잡.
 */
public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, Long> {

    Optional<AnalysisJob> findByJobId(UUID jobId);
}
