package com.dartcommons.user.repositories;

import com.dartcommons.user.entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/*
 * [목적] users 테이블 CRUD — soft delete 필터(deletedAt IS NULL)를 쿼리 메서드로 강제.
 *       OAuthIncompleteAccountCleanupJob용 좀비 계정 탐색·벌크 soft delete 쿼리 제공.
 * [이유] 탈퇴 계정(deleted_at IS NOT NULL)이 인증 흐름에 노출되면 안 됨.
 *       findByEmail 대신 findByEmailAndDeletedAtIsNull을 표준으로 사용.
 *       벌크 soft delete(@Modifying @Query)는 @PreUpdate JPA 훅을 우회 — updated_at 수동 설정 필수.
 * [사이드 임팩트] softDeleteByIdIn()은 @PreUpdate 미호출 → updated_at을 쿼리 내에서 직접 갱신.
 * [수정 시 고려사항] 탈퇴 이메일 재가입 허용 여부 정책에 따라 findByEmail(삭제 포함) 메서드 추가 여부 결정.
 *                  findIncompleteOAuthAccountIds()의 ZOMBIE_GRACE_DAYS는 CleanupJob에서 제어 — 여기선 cutoff 파라미터만 받음.
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmailAndDeletedAtIsNull(String email);

    Optional<UserEntity> findByIdAndDeletedAtIsNull(Long id);

    Optional<UserEntity> findByOauthProviderAndOauthIdAndDeletedAtIsNull(
            UserEntity.OAuthProvider provider, String oauthId);

    boolean existsByEmailAndDeletedAtIsNull(String email);

    /**
     * OAuth 가입 후 온보딩 미완료(onboarding_completed_at IS NULL) 상태로 createdBefore 이전에 생성된
     * 좀비 계정 ID 목록 조회. OAuthIncompleteAccountCleanupJob 전용.
     */
    @Query("""
            SELECT u.id FROM UserEntity u
            WHERE u.oauthProvider IS NOT NULL
              AND u.onboardingCompletedAt IS NULL
              AND u.createdAt < :createdBefore
              AND u.deletedAt IS NULL
            """)
    List<Long> findIncompleteOAuthAccountIds(@Param("createdBefore") OffsetDateTime createdBefore);

    /**
     * 대상 사용자 ID 목록을 벌크 soft delete(deleted_at, updated_at 동시 설정).
     * @PreUpdate JPA 훅이 호출되지 않으므로 updated_at을 직접 갱신.
     */
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query("UPDATE UserEntity u SET u.deletedAt = :now, u.updatedAt = :now WHERE u.id IN :ids")
    int softDeleteByIdIn(@Param("ids") List<Long> ids, @Param("now") OffsetDateTime now);
}
