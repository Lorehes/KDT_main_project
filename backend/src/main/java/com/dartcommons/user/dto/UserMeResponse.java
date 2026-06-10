package com.dartcommons.user.dto;

import com.dartcommons.user.entities.UserEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/*
 * [목적] GET /api/v1/users/me 응답 DTO — 사용자 프로필·알림 설정·BM 티어·휴대폰 인증 상태 정보.
 * [이유] UserEntity를 직접 직렬화하면 passwordHash·phoneNumberEnc·deletedAt 등 민감정보 노출 위험.
 *       응답 DTO를 별도로 두어 노출 필드를 명시적으로 통제. 평문 번호는 절대 노출 금지.
 * [사이드 임팩트] FE AuthUser.tier_expires_at 표시에 사용. Free 구독은 null.
 *               phone_verified 추가 — FE가 signup/phone 흐름 완료 여부를 파악해 알림 채널 UI 제어.
 * [수정 시 고려사항] phoneNumber(복호화 값) 노출이 필요하면 별도 엔드포인트로 분리(보안 리뷰 필수).
 */
public record UserMeResponse(
        Long id,
        String email,
        String nickname,
        String tier,
        @JsonProperty("tier_expires_at")    OffsetDateTime tierExpiresAt,
        @JsonProperty("notify_channel")     String notifyChannel,
        @JsonProperty("notify_enabled")     boolean notifyEnabled,
        @JsonProperty("notify_frequency")   String notifyFrequency,
        @JsonProperty("notify_type_filter") String notifyTypeFilter,
        @JsonProperty("off_hours_allowed")  boolean offHoursAllowed,
        @JsonProperty("phone_verified")     boolean phoneVerified,
        @JsonProperty("terms_agreed_at")    OffsetDateTime termsAgreedAt,
        @JsonProperty("privacy_agreed_at")  OffsetDateTime privacyAgreedAt,
        @JsonProperty("marketing_agreed_at") OffsetDateTime marketingAgreedAt,
        @JsonProperty("created_at")         OffsetDateTime createdAt
) {
    public static UserMeResponse from(UserEntity u) {
        return new UserMeResponse(
                u.getId(),
                u.getEmail(),
                u.getNickname(),
                u.getTier().name(),
                u.getTierExpiresAt(),
                u.getNotifyChannel().name(),
                u.isNotifyEnabled(),
                u.getNotifyFrequency().name(),
                u.getNotifyTypeFilter().name(),
                u.isOffHoursAllowed(),
                u.isPhoneVerified(),
                u.getTermsAgreedAt(),
                u.getPrivacyAgreedAt(),
                u.getMarketingAgreedAt(),
                u.getCreatedAt()
        );
    }
}
