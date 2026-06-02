package com.dartcommons.disclosure.repositories;

import com.dartcommons.disclosure.entities.BackfillJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/*
 * [목적] BackfillJob CRUD + jobId 조회.
 * [이유] 외부에 노출되는 식별자는 UUID(jobId) — DB PK(id)는 내부 전용.
 * [수정 시 고려사항] 진행 중 잡 조회(findByStatus)는 cleanup/모니터링 추가 시 도입.
 */
public interface BackfillJobRepository extends JpaRepository<BackfillJob, Long> {

    Optional<BackfillJob> findByJobId(UUID jobId);
}
