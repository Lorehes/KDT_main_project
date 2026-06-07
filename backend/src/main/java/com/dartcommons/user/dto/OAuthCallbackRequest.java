package com.dartcommons.user.dto;

import jakarta.validation.constraints.NotBlank;

/*
 * [목적] POST /api/v1/auth/oauth/{provider}/callback 요청 DTO — 인가 코드와 state CSRF 토큰 수신.
 * [이유] 프론트엔드가 provider 리다이렉트 콜백에서 code·state를 받아 서버로 전달하는 형태.
 *       @NotBlank로 빈 코드/state 요청 즉시 400 차단.
 * [사이드 임팩트] state 검증은 AuthService에서 Caffeine 캐시 대조 후 처리.
 * [수정 시 고려사항] PKCE 도입 시 code_verifier 필드 추가.
 */
public record OAuthCallbackRequest(
        @NotBlank(message = "인가 코드를 입력하세요") String code,
        @NotBlank(message = "state 값을 입력하세요")  String state
) {}
