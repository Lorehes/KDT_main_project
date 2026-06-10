package com.dartcommons.user.entities;

import com.dartcommons.shared.enums.Tier;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] users 테이블(V1+V7 마이그레이션) JPA 엔티티 — 사용자·인증·BM 등급·알림 설정 루트 엔티티.
 * [이유] soft delete(deleted_at) + OAuth2 계정(password_hash nullable) 지원.
 *       V7 알림 빈도/필터/거래시간외 컬럼 포함(V7__add_notification_settings_to_users.sql).
 *       Enum은 VARCHAR+CHECK DB 제약과 @Enumerated(STRING) 동기화(db_schema §3.1, CLAUDE.md §6-3).
 * [사이드 임팩트] portfolios/notifications/consent_logs/feedbacks가 users.id FK 참조.
 *               soft delete 후 email UNIQUE 충돌 가능 — findByEmailAndDeletedAtIsNull 사용 필수.
 * [수정 시 고려사항] phone_number_enc는 BYTEA — AesGcmEncryptor로만 암복호. 평문 저장/로깅 절대 금지.
 *                  tier 변경(Pro 구독 만료)은 별도 스케줄러 담당 — 여기선 단순 필드.
 */
@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class UserEntity {

    public enum OAuthProvider { KAKAO, GOOGLE, NAVER }
    public enum NotifyChannel  { KAKAO, TELEGRAM, EMAIL }
    public enum NotifyFrequency{ INSTANT, DAILY_1, DAILY_2, WEEKLY }
    public enum NotifyTypeFilter{ POSITIVE_ONLY, NEGATIVE_ONLY, ALL }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "email", nullable = false, unique = true, length = 255)
    private String email;

    /** OAuth 전용 계정은 null. BCrypt 해시 저장 — 평문 저장 절대 금지. */
    @Column(name = "password_hash", length = 255)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "oauth_provider", length = 10)
    private OAuthProvider oauthProvider;

    @Column(name = "oauth_id", length = 255)
    private String oauthId;

    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    /** AES-256-GCM 암호화. 알림톡 사용 시만 수집(선택). 평문 로깅 금지. */
    @Column(name = "phone_number_enc")
    private byte[] phoneNumberEnc;

    @Enumerated(EnumType.STRING)
    @Column(name = "tier", nullable = false, length = 10)
    @Builder.Default
    private Tier tier = Tier.FREE;

    @Column(name = "tier_expires_at")
    private OffsetDateTime tierExpiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_channel", nullable = false, length = 10)
    @Builder.Default
    private NotifyChannel notifyChannel = NotifyChannel.KAKAO;

    @Column(name = "notify_enabled", nullable = false)
    @Builder.Default
    private boolean notifyEnabled = true;

    @Column(name = "terms_agreed_at", nullable = false)
    private OffsetDateTime termsAgreedAt;

    @Column(name = "privacy_agreed_at", nullable = false)
    private OffsetDateTime privacyAgreedAt;

    @Column(name = "marketing_agreed_at")
    private OffsetDateTime marketingAgreedAt;

    // V7 알림 설정 컬럼
    @Enumerated(EnumType.STRING)
    @Column(name = "notify_frequency", nullable = false, length = 10)
    @Builder.Default
    private NotifyFrequency notifyFrequency = NotifyFrequency.INSTANT;

    @Enumerated(EnumType.STRING)
    @Column(name = "notify_type_filter", nullable = false, length = 15)
    @Builder.Default
    private NotifyTypeFilter notifyTypeFilter = NotifyTypeFilter.ALL;

    @Column(name = "off_hours_allowed", nullable = false)
    @Builder.Default
    private boolean offHoursAllowed = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** soft delete — deleted_at 설정으로 논리 삭제. 실제 행 삭제는 GDPR 정책 배치 담당. */
    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    private void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    public void updateNickname(String nickname) {
        this.nickname = nickname;
    }

    public void updateNotifySettings(NotifyChannel channel, boolean enabled,
                                     NotifyFrequency frequency, NotifyTypeFilter typeFilter,
                                     boolean offHoursAllowed) {
        this.notifyChannel    = channel;
        this.notifyEnabled    = enabled;
        this.notifyFrequency  = frequency;
        this.notifyTypeFilter = typeFilter;
        this.offHoursAllowed  = offHoursAllowed;
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
