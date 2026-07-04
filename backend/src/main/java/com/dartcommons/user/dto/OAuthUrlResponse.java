package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] GET /api/v1/auth/oauth/{provider}/url 응답 DTO — 프론트엔드가 리다이렉트할 OAuth 인가 URL + state 반환.
 * [이유] state를 서버가 생성해 Caffeine 캐시에 저장한 후 클라이언트에 전달 → CSRF 방지.
 *       프론트엔드가 인가 URL로 리다이렉트 후 콜백에서 code+state를 서버로 전송(POST /callback).
 * [사이드 임팩트] state TTL 5분 — 응답 후 5분 내 콜백이 없으면 state 만료 → 인증 재시도 필요.
 * [수정 시 고려사항] 모바일 딥링크 방식 전환 시 url 대신 scheme://... 형태로 변경.
 */
public record OAuthUrlResponse(
        String url,
        @JsonProperty("state") String state
) {}
