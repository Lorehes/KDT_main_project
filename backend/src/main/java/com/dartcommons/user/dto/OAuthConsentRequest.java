package com.dartcommons.user.dto;

import jakarta.validation.constraints.AssertTrue;

/*
 * [목적] 소셜 로그인 신규 가입 후 약관 동의 수집 요청 DTO — POST /api/v1/users/me/oauth-consent.
 * [이유] 이메일 가입(SignupRequest)과 달리 소셜 가입은 계정 생성 후 별도 단계에서 동의를 수집.
 *       email/password 없이 동의 항목만 받아 ConsentService.recordSignupConsents()에 위임.
 * [사이드 임팩트] 이 요청은 인증된 사용자(JWT 보유)만 호출 가능 — /api/v1/users/me/** 인증 필요 경로.
 *               중복 호출 시 consent_logs에 동의 이력이 추가 기록됨 (INSERT-only 정책 — 덮어쓰기 없음).
 * [수정 시 고려사항] 필수 동의 항목(TERMS·PRIVACY·DISCLAIMER) 추가 시 BE+FE 동시 수정 필요.
 *                  policy_version 선택 인자 추가 시 ConsentService.recordSignupConsents 시그니처와 동기화.
 */
public record OAuthConsentRequest(
        @AssertTrue(message = "서비스 이용약관에 동의해주세요")   boolean termsAgreed,
        @AssertTrue(message = "개인정보 처리방침에 동의해주세요") boolean privacyAgreed,
        @AssertTrue(message = "면책 조항에 동의해주세요")        boolean disclaimerAgreed,
        boolean marketingAgreed
) {}
