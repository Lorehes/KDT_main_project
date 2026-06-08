package com.dartcommons.user.repositories;

import com.dartcommons.user.entities.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * [목적] users 테이블 CRUD — soft delete 필터(deletedAt IS NULL)를 쿼리 메서드로 강제.
 * [이유] 탈퇴 계정(deleted_at IS NOT NULL)이 인증 흐름에 노출되면 안 됨.
 *       findByEmail 대신 findByEmailAndDeletedAtIsNull을 표준으로 사용.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] 탈퇴 이메일 재가입 허용 여부 정책에 따라 findByEmail(삭제 포함) 메서드 추가 여부 결정.
 */
public interface UserRepository extends JpaRepository<UserEntity, Long> {

    Optional<UserEntity> findByEmailAndDeletedAtIsNull(String email);

    Optional<UserEntity> findByIdAndDeletedAtIsNull(Long id);

    Optional<UserEntity> findByOauthProviderAndOauthIdAndDeletedAtIsNull(
            UserEntity.OAuthProvider provider, String oauthId);

    boolean existsByEmailAndDeletedAtIsNull(String email);
}
