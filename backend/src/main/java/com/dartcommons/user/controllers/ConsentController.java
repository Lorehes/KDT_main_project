package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.ConsentRequest;
import com.dartcommons.user.dto.ConsentStatusResponse;
import com.dartcommons.user.services.ConsentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 동의 이력 REST 엔드포인트 — POST /api/v1/consents (재동의), GET /api/v1/consents/status.
 * [이유] 초기 가입 동의는 POST /auth/signup에서 처리(SignupRequest 내 포함).
 *       이 컨트롤러는 약관 버전 변경 시 기존 사용자의 재동의 플로우 전용.
 *       FE는 /consents/status → requires_renewal=true 시 /signup/terms로 강제 이동.
 * [사이드 임팩트] POST /consents는 consent_logs에 INSERT-only 추가 — 기존 이력 불변 유지.
 *               requires_renewal 산출은 TERMS·PRIVACY 최신 policy_version 대조.
 * [수정 시 고려사항] GET /consents/status는 앱 시작 시 또는 로그인 직후 FE가 호출 권장.
 *                  DISCLAIMER 재동의 정책 추가 시 ConsentService.getStatus() 로직 확장.
 */
@RestController
@RequestMapping("/api/v1/consents")
public class ConsentController {

    private final ConsentService consentService;

    public ConsentController(ConsentService consentService) {
        this.consentService = consentService;
    }

    /**
     * 재동의 기록 — termsVersion·privacyVersion 필드 존재 자체가 동의 의사 표시.
     * 실제 동의 버전을 서비스에 전달해 consent_logs에 정확히 기록(감사 로그 정확도 보장).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void postConsent(@AuthenticationPrincipal Long userId,
                            @Valid @RequestBody ConsentRequest request) {
        consentService.recordReConsents(userId, request.termsVersion(), request.privacyVersion(), request.marketingOptIn());
    }

    /** 최신 동의 상태 조회 — requires_renewal=true 이면 FE가 재동의 흐름 진입. */
    @GetMapping("/status")
    public ConsentStatusResponse getStatus(@AuthenticationPrincipal Long userId) {
        return consentService.getStatus(userId);
    }
}
