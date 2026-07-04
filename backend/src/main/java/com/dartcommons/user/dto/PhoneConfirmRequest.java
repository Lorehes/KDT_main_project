package com.dartcommons.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * [목적] POST /api/v1/users/me/phone/verify/confirm 요청 DTO — OTP 코드 유효성 검증.
 * [이유] 6자리 숫자만 허용 — 서버 캐시의 코드 형식과 동일. 비형식 입력은 조기 거부.
 * [사이드 임팩트] 검증 실패 시 400 Bad Request (캐시 조회 전 차단).
 * [수정 시 고려사항] OTP 자릿수 변경 시 PhoneVerificationService.generateOtp()와 동기화 필요.
 */
public record PhoneConfirmRequest(
        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "6자리 숫자 인증번호를 입력해주세요.")
        String code
) {}
