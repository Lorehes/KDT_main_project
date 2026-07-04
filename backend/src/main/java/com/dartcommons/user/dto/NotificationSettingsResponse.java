package com.dartcommons.user.dto;

import com.dartcommons.user.entities.UserEntity;
import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] GET/PUT /api/v1/users/me/notifications 응답 DTO — 현재 알림 설정 상태.
 * [이유] UserMeResponse에 포함된 설정과 동일하지만, 알림 설정 전용 엔드포인트의 명시적 응답 타입으로 분리.
 *       클라이언트가 알림 설정만 갱신·조회할 때 전체 프로필 응답보다 경량.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] UserMeResponse와 알림 관련 필드가 중복 — 후속 리팩터링 시 통합 검토 가능.
 */
public record NotificationSettingsResponse(
        String channel,
        boolean enabled,
        String frequency,
        @JsonProperty("type_filter")       String typeFilter,
        @JsonProperty("off_hours_allowed") boolean offHoursAllowed
) {
    public static NotificationSettingsResponse from(UserEntity u) {
        return new NotificationSettingsResponse(
                u.getNotifyChannel().name(),
                u.isNotifyEnabled(),
                u.getNotifyFrequency().name(),
                u.getNotifyTypeFilter().name(),
                u.isOffHoursAllowed()
        );
    }
}
