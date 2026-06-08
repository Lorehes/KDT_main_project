package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] 인증 성공 응답 DTO — access_token·refresh_token·만료 정보 포함.
 * [이유] snake_case 필드명은 REST API 컨벤션(프론트엔드 협의). Jackson @JsonProperty로 직렬화.
 *       expiresIn은 클라이언트가 자동 갱신 타이머 설정에 사용.
 * [사이드 임팩트] refresh_token은 응답 후 HttpOnly Cookie 또는 클라이언트 보안 저장소에 보관 권장.
 *               응답 로그에 토큰 값 노출 금지(SecretMasker 적용).
 * [수정 시 고려사항] HttpOnly Cookie 방식으로 전환 시 이 필드를 제거하고 Set-Cookie 헤더로 이동.
 */
public record AuthResponse(
        @JsonProperty("access_token")  String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("token_type")    String tokenType,
        @JsonProperty("expires_in")    long   expiresIn
) {
    public static AuthResponse of(String accessToken, String refreshToken, long accessTtlMinutes) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", accessTtlMinutes * 60);
    }
}
