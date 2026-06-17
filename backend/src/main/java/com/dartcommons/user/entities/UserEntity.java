package com.dartcommons.user.entities;

import com.dartcommons.shared.enums.Tier;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] users 테이블(V1+V7+V10 마이그레이션) JPA 엔티티 — 사용자·인증·BM 등급·알림 설정 루트 엔티티.
 * [이유] soft delete(deleted_at) + OAuth2 계정(password_hash nullable) 지원.
 *       V7 알림 빈도/필터/거래시간외 컬럼 포함.
 *       V10 onboarding_completed_at: OAuth 로그인 is_new_user 판단 기준. NULL=미완료, NOT NULL=완료.
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

    /** OTP 인증 완료 여부(V17). phone_number_enc 저장과 독립 — 재인증 시 false 리셋 가능. */
    @Column(name = "phone_verified", nullable = false)
    @Builder.Default
    private boolean phoneVerified = false;

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

    /** OAuth 로그인 is_new_user 판단 기준. NULL=온보딩 미완료, NOT NULL=완료. V10 마이그레이션 추가. */
    @Column(name = "onboarding_completed_at")
    private OffsetDateTime onboardingCompletedAt;

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

    /** 전화번호 암호화 저장 + OTP 인증 완료 마킹. 번호 변경 시에도 이 메서드를 사용해 재인증 상태를 갱신. */
    public void completePhoneVerification(byte[] encryptedPhone) {
        this.phoneNumberEnc = encryptedPhone;
        this.phoneVerified  = true;
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

    /** 온보딩 완료 마킹 — /signup/complete 도달 시 1회 설정. 이미 설정된 경우 멱등 처리. */
    public void completeOnboarding() {
        if (this.onboardingCompletedAt == null) {
            this.onboardingCompletedAt = OffsetDateTime.now();
        }
    }

    public void softDelete() {
        this.deletedAt = OffsetDateTime.now();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
