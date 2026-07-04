package com.dartcommons.user.repositories;

import com.dartcommons.user.entities.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
 * [목적] refresh_tokens 테이블 CRUD — token_hash 기반 조회·삭제(login/refresh/logout 핵심 경로).
 *       OAuthIncompleteAccountCleanupJob용 벌크 삭제(deleteByUserIdIn) 제공.
 * [이유] rotation 패턴: 갱신 시 deleteByTokenHash(기존) → save(신규). logout 시 deleteByTokenHash.
 *       만료 토큰 정기 삭제를 위한 deleteByExpiresAtBefore 제공.
 *       soft delete한 사용자의 토큰은 ON DELETE CASCADE가 아닌 애플리케이션 레이어에서 명시적 삭제.
 * [사이드 임팩트] deleteByUserId는 단건(force logout) / deleteByUserIdIn은 배치(좀비 계정 정리).
 * [수정 시 고려사항] 다중 기기 지원 시 device_id 컬럼 추가 고려. 대량 만료 삭제는 배치로 처리.
 */
public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    void deleteByUserId(Long userId);

    @Transactional
    @Modifying
    @Query("DELETE FROM RefreshTokenEntity r WHERE r.expiresAt < :now")
    int deleteExpiredTokens(@Param("now") OffsetDateTime now);

    /** 배치 soft delete 전 대상 사용자 토큰 일괄 삭제 — OAuthIncompleteAccountCleanupJob 전용. */
    @Transactional
    @Modifying
    @Query("DELETE FROM RefreshTokenEntity r WHERE r.userId IN :userIds")
    int deleteByUserIdIn(@Param("userIds") List<Long> userIds);
}
