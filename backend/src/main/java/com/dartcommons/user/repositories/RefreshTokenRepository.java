package com.dartcommons.user.repositories;

import com.dartcommons.user.entities.RefreshTokenEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/*
 * [목적] refresh_tokens 테이블 CRUD — token_hash 기반 조회·삭제(login/refresh/logout 핵심 경로).
 * [이유] rotation 패턴: 갱신 시 deleteByTokenHash(기존) → save(신규). logout 시 deleteByTokenHash.
 *       만료 토큰 정기 삭제를 위한 deleteByExpiresAtBefore 제공.
 * [사이드 임팩트] deleteByUserId는 모든 기기 로그아웃(force logout) 시 사용.
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
}
