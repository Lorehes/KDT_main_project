package com.dartcommons.user.dto;

import com.dartcommons.user.entities.UserEntity;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;

/*
 * [목적] PUT /api/v1/users/me/notifications 요청 DTO — 알림 채널·빈도·필터 설정 변경.
 * [이유] UserEntity 내부 Enum을 직접 노출하면 API 계약이 엔티티 모델에 강결합.
 *       그러나 Enum 값이 안정적(KAKAO/TELEGRAM/EMAIL 등)이고 변경 빈도 낮아 직접 사용.
 *       Jackson이 Enum 이름 문자열로 자동 역직렬화.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] channel=KAKAO 선택 시 phone_number 등록 여부 검증 추가 가능(후속).
 */
public record NotificationSettingsRequest(
        @NotNull UserEntity.NotifyChannel channel,
        boolean enabled,
        @NotNull UserEntity.NotifyFrequency frequency,
        @JsonProperty("type_filter") @NotNull UserEntity.NotifyTypeFilter typeFilter,
        @JsonProperty("off_hours_allowed") boolean offHoursAllowed
) {}
