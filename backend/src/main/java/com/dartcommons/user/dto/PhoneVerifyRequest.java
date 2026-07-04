package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * [목적] POST /api/v1/users/me/phone/verify 요청 DTO — 번호 유효성 검증.
 * [이유] 010으로 시작하는 11자리 숫자만 허용 — 알림톡 수신 가능 번호. 하이픈 포함 입력은 정규식 제외.
 * [사이드 임팩트] 검증 실패 시 400 Bad Request. FE에서 하이픈 제거 후 전송 권장.
 * [수정 시 고려사항] 해외 번호 지원 시 패턴 확장 필요. 평문 로깅 절대 금지(CLAUDE.md §7).
 */
public record PhoneVerifyRequest(
        @NotBlank
        @Pattern(regexp = "^010\\d{8}$", message = "010으로 시작하는 11자리 숫자만 허용됩니다.")
        @JsonProperty("phone_number")
        String phoneNumber
) {}
