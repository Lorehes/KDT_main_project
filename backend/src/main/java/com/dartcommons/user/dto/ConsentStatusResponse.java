package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.List;

/*
 * [목적] GET /api/v1/consents/status 응답 DTO — 동의 유형별 최신 상태 + 재동의 필요 여부.
 * [이유] FE가 requires_renewal=true 시 /signup/terms로 강제 이동해 정보통신망법 §22 동의 갱신 흐름 트리거.
 *       각 consent_type의 최신 agreed + policy_version을 병렬 노출해 FE 세분화 처리 가능.
 * [사이드 임팩트] OAuth 신규 가입 분기(frontend-oauth-social R7)와 동일 흐름에서 사용.
 * [수정 시 고려사항] 약관 버전 정책 변경 시 CURRENT_POLICY_VERSION을 ConsentService에서 중앙 관리.
 *                  requires_renewal 산출 로직은 ConsentService.getStatus()에 집중 — 이 DTO는 순수 데이터 컨테이너.
 */
public record ConsentStatusResponse(
        @JsonProperty("requires_renewal") boolean requiresRenewal,
        List<ConsentItem> consents
) {
    public record ConsentItem(
            @JsonProperty("consent_type")       String consentType,
            boolean agreed,
            @JsonProperty("policy_version")     String policyVersion,
            @JsonProperty("is_current_version") boolean isCurrentVersion,
            @JsonProperty("agreed_at")          OffsetDateTime agreedAt
    ) {}
}
