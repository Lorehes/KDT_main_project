package com.dartcommons.user.repositories;

import com.dartcommons.user.entities.ConsentLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/*
 * [목적] consent_logs 테이블 조회 — INSERT-only 운영 규칙에 따라 save만 허용, delete 호출 금지.
 * [이유] 동의 이력은 법적 증거 — 삭제·수정 불가(db_schema §3.7, 통합기획서 §11.1).
 *       최신 동의 상태 = (user_id, consent_type) MAX(agreed_at) 행.
 * [사이드 임팩트] JpaRepository의 delete 메서드는 상속되지만 서비스 계층에서 호출 금지.
 * [수정 시 고려사항] 메이저 버전 재동의 정책 도입 시 policy_version 필터 쿼리 추가.
 */
public interface ConsentLogRepository extends JpaRepository<ConsentLogEntity, Long> {

    List<ConsentLogEntity> findByUserIdOrderByAgreedAtDesc(Long userId);

    /** (user_id, consent_type) 기준 최신 동의 상태 조회 (MAX(agreed_at)). */
    @Query("""
            SELECT c FROM ConsentLogEntity c
            WHERE c.userId = :userId AND c.consentType = :type
            ORDER BY c.agreedAt DESC
            LIMIT 1
            """)
    Optional<ConsentLogEntity> findLatestByUserIdAndType(
            @Param("userId") Long userId,
            @Param("type") ConsentLogEntity.ConsentType type);

    /** 사용자의 모든 consent_type 최신 행을 단일 쿼리로 조회 — getStatus() N+1 제거. */
    @Query("""
            SELECT c FROM ConsentLogEntity c
            WHERE c.userId = :userId
              AND c.agreedAt = (
                    SELECT MAX(c2.agreedAt) FROM ConsentLogEntity c2
                    WHERE c2.userId = c.userId AND c2.consentType = c.consentType)
            """)
    List<ConsentLogEntity> findLatestAllByUserId(@Param("userId") Long userId);
}
