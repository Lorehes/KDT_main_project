package com.dartcommons.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * [목적] POST /api/v1/auth/email/verify 요청 DTO — 이메일 + OTP 코드 유효성 검증.
 * [이유] 캐시 조회 전 형식 오류를 Bean Validation으로 조기 차단. 6자리 숫자 외 입력은 400으로 즉시 거부.
 * [사이드 임팩트] 검증 실패 시 400. code 필드 로그 평문 출력 금지(CLAUDE.md §7).
 * [수정 시 고려사항] OTP 자릿수 변경 시 EmailVerificationService.generateOtp()와 동기화 필요.
 */
public record EmailVerifyOtpRequest(
        @NotBlank
        @Email(message = "올바른 이메일 형식을 입력해주세요.")
        String email,

        @NotBlank
        @Pattern(regexp = "^\\d{6}$", message = "6자리 숫자 인증번호를 입력해주세요.")
        String code
) {}
