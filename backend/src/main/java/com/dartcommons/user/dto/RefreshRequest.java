package com.dartcommons.user.dto;

import jakarta.validation.constraints.NotBlank;

/*
 * [목적] access token 갱신 요청 DTO — raw refresh token 수신.
 * [이유] refresh token은 클라이언트에서 raw 값을 전송. 서버는 SHA-256 해시 후 DB와 비교.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] HttpOnly Cookie 방식 전환 시 이 DTO 불필요(쿠키에서 직접 추출).
 */
public record RefreshRequest(@NotBlank String refreshToken) {}
