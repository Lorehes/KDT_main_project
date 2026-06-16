package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.OAuthConsentRequest;
import com.dartcommons.user.dto.PhoneConfirmRequest;
import com.dartcommons.user.dto.PhoneVerifyRequest;
import com.dartcommons.user.dto.UpdateMeRequest;
import com.dartcommons.user.dto.UserMeResponse;
import com.dartcommons.user.services.ConsentService;
import com.dartcommons.user.services.PhoneVerificationService;
import com.dartcommons.user.services.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 사용자 프로필 REST 엔드포인트 — GET/PATCH/DELETE /api/v1/users/me + 휴대폰 OTP 인증 2종 + 소셜 약관 동의.
 * [이유] "me" 패턴으로 본인 리소스만 접근 — 별도 userId 경로 파라미터 불필요, IDOR 위험 제거.
 *       JWT 필터가 principal에 userId(Long)를 설정 → @AuthenticationPrincipal로 주입.
 *       소셜 약관 동의(POST /me/oauth-consent)는 /api/v1/users/** 인증 필요 경로에 배치 — /api/v1/auth/**의 permitAll과 분리.
 * [사이드 임팩트] DELETE /me → soft delete + 전 기기 refresh token 삭제. access token은 만료(30분) 대기.
 *               POST /phone/verify → Caffeine 5분 TTL OTP 발송 + rate limit(1분 1회, 시간당 5회).
 *               POST /phone/verify/confirm → OTP 검증 성공 시 phone_number_enc 갱신 + phone_verified=true.
 *               POST /me/oauth-consent → consent_logs INSERT — 중복 호출 시 이력 추가(INSERT-only 정책).
 * [수정 시 고려사항] PATCH에 nickname 외 필드 추가 시 UpdateMeRequest 확장.
 *                  탈퇴 전 확인 코드(이메일 재인증 등)가 필요하면 2-step 흐름 추가.
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserService               userService;
    private final PhoneVerificationService  phoneVerificationService;
    private final ConsentService            consentService;

    public UserController(UserService userService,
                          PhoneVerificationService phoneVerificationService,
                          ConsentService consentService) {
        this.userService              = userService;
        this.phoneVerificationService = phoneVerificationService;
        this.consentService           = consentService;
    }

    @GetMapping("/me")
    public UserMeResponse getMe(@AuthenticationPrincipal Long userId) {
        return userService.getMe(userId);
    }

    @PatchMapping("/me")
    public UserMeResponse updateMe(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody UpdateMeRequest request) {
        return userService.updateMe(userId, request);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteMe(@AuthenticationPrincipal Long userId) {
        userService.softDeleteMe(userId);
    }

    /** OTP 발송 — rate limit(1분 1회, 시간당 5회) 초과 시 429. */
    @PostMapping("/me/phone/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendPhoneVerification(@AuthenticationPrincipal Long userId,
                                      @Valid @RequestBody PhoneVerifyRequest request) {
        phoneVerificationService.sendVerification(userId, request.phoneNumber());
    }

    /** OTP 검증 — 성공 시 phone_verified=true, 만료 시 410, 불일치 시 400. */
    @PostMapping("/me/phone/verify/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void confirmPhoneVerification(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody PhoneConfirmRequest request) {
        phoneVerificationService.confirmVerification(userId, request.code());
    }

    /**
     * 소셜 로그인 신규 가입 후 약관 동의 저장 — POST /api/v1/users/me/oauth-consent.
     * OAuth 콜백에서 is_new_user=true를 받은 FE가 /signup/terms?oauth=true 화면에서 동의 후 호출.
     * 이미 필수 동의 완료 시 멱등 처리(INSERT 없이 204) — 네트워크 재시도·이중 클릭 방어.
     * 204 No Content 반환.
     */
    @PostMapping("/me/oauth-consent")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordOAuthConsent(@AuthenticationPrincipal Long userId,
                                   @Valid @RequestBody OAuthConsentRequest request) {
        if (consentService.hasRequiredConsents(userId)) return;
        consentService.recordSignupConsents(userId,
                request.termsAgreed(),
                request.privacyAgreed(),
                request.disclaimerAgreed(),
                request.marketingAgreed());
    }
}
