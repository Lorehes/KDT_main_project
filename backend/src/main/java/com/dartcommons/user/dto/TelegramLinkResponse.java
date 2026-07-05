package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] POST /api/v1/notifications/telegram/link 응답 DTO — 텔레그램 딥링크.
 * [이유] FE가 새 창으로 열 t.me 딥링크 1필드 — snake_case 직렬화(deep_link) API 규약 정합.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] 토큰 TTL을 FE에 노출하려면 expires_in_seconds 필드 추가.
 */
public record TelegramLinkResponse(
        @JsonProperty("deep_link") String deepLink
) {
}
