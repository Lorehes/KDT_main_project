package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * [목적] POST /api/v1/consents 요청 DTO — 재동의 흐름에서 약관·개인정보·마케팅 동의 일괄 제출.
 * [이유] 초기 가입 동의(SignupRequest)와 재동의(이 DTO)를 분리 — 가입 플로우는 terms/privacy/disclaimer/marketing 4종,
 *       재동의는 버전 명시 + 선택 마케팅 포함 3종. policy_version은 FE가 현재 화면에 표시한 버전을 전송해 추적.
 * [사이드 임팩트] ConsentService.recordReConsents()가 3개 consent_log 행을 INSERT-only 기록(TERMS·PRIVACY·MARKETING).
 * [수정 시 고려사항] terms/privacy는 필수 — agreed=false 시 서비스 계층에서 422 차단.
 *                  marketing은 선택(null 또는 false 허용). policy_version은 ^v\d+\.\d+$ 형식 검증.
 */
public record ConsentRequest(
        @NotBlank
        @Pattern(regexp = "^v\\d+\\.\\d+$", message = "policy_version 형식은 v{major}.{minor}이어야 합니다.")
        @JsonProperty("terms_version")
        String termsVersion,

        @NotBlank
        @Pattern(regexp = "^v\\d+\\.\\d+$", message = "policy_version 형식은 v{major}.{minor}이어야 합니다.")
        @JsonProperty("privacy_version")
        String privacyVersion,

        @JsonProperty("marketing_opt_in")
        Boolean marketingOptIn
) {}
