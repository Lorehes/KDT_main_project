package com.dartcommons.user.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] refresh_tokens 테이블(V14) JPA 엔티티 — refresh token SHA-256 해시 서버측 저장.
 *       logout 즉시 무효화 + rotation(갱신 시 기존 삭제+신규 발급)을 위한 서버 레코드.
 * [이유] Stateless JWT만으로는 logout 시 즉시 무효화 불가. SHA-256 해시만 저장해
 *       DB 유출 시에도 원본 토큰을 얻을 수 없음(JwtTokenProvider.hashRefreshToken).
 * [사이드 임팩트] 모든 로그인/갱신/로그아웃이 이 테이블에 쓰기 — 트래픽 집중 시 병목 가능.
 *               ON DELETE CASCADE(users) — 회원 탈퇴(soft delete는 DB row 유지) 시 cascade 미발생.
 *               실제 사용자 행 삭제는 GDPR 배치에서 처리, 그 시점에 cascade 삭제됨.
 * [수정 시 고려사항] 만료된 토큰 정기 삭제(@Scheduled) 추가 권장. Redis 도입 시 이 테이블 불필요.
 *                  다중 기기 로그인은 user_id당 복수 행 허용 — 동기화 정책은 후속 이슈.
 */
@Entity
@Table(name = "refresh_tokens")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class RefreshTokenEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** SHA-256(raw_refresh_token) hex 64자. DB에 원본 토큰 저장 절대 금지. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public boolean isExpired() {
        return OffsetDateTime.now().isAfter(expiresAt);
    }
}
