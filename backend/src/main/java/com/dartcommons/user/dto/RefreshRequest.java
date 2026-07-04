package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

/*
 * [목적] access token 갱신·로그아웃 요청 DTO — raw refresh token 수신.
 * [이유] FE Route Handler(`/api/auth/refresh`, `/api/auth/logout`)가 snake_case JSON으로 전송.
 *       @JsonProperty("refresh_token") 없으면 Jackson이 camelCase로만 역직렬화해 @NotBlank 위반 400 발생.
 * [사이드 임팩트] AuthController.refresh() · AuthController.logout() 모두 이 DTO 사용.
 * [수정 시 고려사항] HttpOnly Cookie 방식 전환 시 이 DTO 불필요(쿠키에서 직접 추출).
 */
public record RefreshRequest(@JsonProperty("refresh_token") @NotBlank String refreshToken) {}
