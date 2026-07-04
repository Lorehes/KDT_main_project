package com.dartcommons.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/*
 * [목적] POST /api/v1/auth/email/send-otp 요청 DTO — 이메일 형식 유효성 검증.
 * [이유] 유효하지 않은 이메일로 SMTP 발송 시도를 Bean Validation 레이어에서 조기 차단.
 * [사이드 임팩트] 검증 실패 시 400 Bad Request. EmailVerificationService 호출 전 차단.
 * [수정 시 고려사항] 허용 도메인 화이트리스트 적용 시 커스텀 @ValidEmailDomain 어노테이션 추가.
 */
public record EmailSendOtpRequest(
        @NotBlank
        @Email(message = "올바른 이메일 형식을 입력해주세요.")
        String email
) {}
