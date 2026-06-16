package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] 인증 성공 응답 DTO — access_token·refresh_token·만료 정보·신규 사용자 여부 포함.
 * [이유] snake_case 필드명은 REST API 컨벤션(프론트엔드 협의). Jackson @JsonProperty로 직렬화.
 *       expiresIn은 클라이언트가 자동 갱신 타이머 설정에 사용.
 *       is_new_user: OAuth 신규 가입 또는 약관 동의 미완료 사용자 식별 — FE가 /signup/terms?oauth=true 분기에 사용.
 * [사이드 임팩트] refresh_token은 응답 후 HttpOnly Cookie 또는 클라이언트 보안 저장소에 보관 권장.
 *               응답 로그에 토큰 값 노출 금지(SecretMasker 적용).
 * [수정 시 고려사항] HttpOnly Cookie 방식으로 전환 시 토큰 필드를 제거하고 Set-Cookie 헤더로 이동.
 *                  is_new_user 활용 범위 확장 시(예: 온보딩 추가 단계) FE route.ts 분기 로직 함께 수정.
 */
public record AuthResponse(
        @JsonProperty("access_token")  String  accessToken,
        @JsonProperty("refresh_token") String  refreshToken,
        @JsonProperty("token_type")    String  tokenType,
        @JsonProperty("expires_in")    long    expiresIn,
        @JsonProperty("is_new_user")   boolean isNewUser
) {
    public static AuthResponse of(String accessToken, String refreshToken, long accessTtlMinutes) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", accessTtlMinutes * 60, false);
    }

    public static AuthResponse ofNew(String accessToken, String refreshToken, long accessTtlMinutes) {
        return new AuthResponse(accessToken, refreshToken, "Bearer", accessTtlMinutes * 60, true);
    }
}
