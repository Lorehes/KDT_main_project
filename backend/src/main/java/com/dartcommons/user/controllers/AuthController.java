package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.AuthResponse;
import com.dartcommons.user.dto.EmailSendOtpRequest;
import com.dartcommons.user.dto.EmailVerifyOtpRequest;
import com.dartcommons.user.dto.LoginRequest;
import com.dartcommons.user.dto.OAuthCallbackRequest;
import com.dartcommons.user.dto.OAuthUrlResponse;
import com.dartcommons.user.dto.RefreshRequest;
import com.dartcommons.user.dto.SignupRequest;
import com.dartcommons.user.services.AuthService;
import com.dartcommons.user.services.EmailVerificationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 이메일·OAuth 인증 REST 엔드포인트 — email OTP / signup / login / refresh / logout / oauth URL 생성·콜백.
 *       /api/v1/auth/** 는 SecurityConfig userFilterChain에서 permitAll (토큰 없이 접근 가능).
 * [이유] 회원가입·로그인·OAuth는 공개 엔드포인트. 갱신·로그아웃은 raw refresh token을 body로 수신.
 *       이메일 OTP(R1·R2)는 signup 전 단계로 인증 불필요 — /api/v1/auth/** 일괄 permitAll 범위에 포함.
 *       OAuth flow: GET /oauth/{provider}/url 로 인가 URL 수신 → provider 리다이렉트 → POST /oauth/{provider}/callback.
 * [사이드 임팩트] /api/v1/auth/** permitAll 정책으로 JWT 없어도 도달 — AuthService·EmailVerificationService가 검증 책임.
 *               logout 응답은 204(No Content) — refresh token 무효화만 수행.
 *               OAuth provider가 지원 목록에 없으면 AuthService에서 400 반환.
 * [수정 시 고려사항] logout을 HttpOnly Cookie로 전환 시 @CookieValue 방식.
 *                  OAuth state를 Cookie에 담는 방식으로 전환 시 GET /url에 Set-Cookie 헤더 추가.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService               authService;
    private final EmailVerificationService  emailVerificationService;

    public AuthController(AuthService authService,
                          EmailVerificationService emailVerificationService) {
        this.authService              = authService;
        this.emailVerificationService = emailVerificationService;
    }

    // ── 이메일 OTP ────────────────────────────────────────────────────────

    /** OTP 발송 — 이미 가입된 이메일이면 409, rate limit 초과 시 429. */
    @PostMapping("/email/send-otp")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void sendEmailOtp(@Valid @RequestBody EmailSendOtpRequest request) {
        emailVerificationService.sendOtp(request.email());
    }

    /** OTP 검증 — 만료 시 410, 불일치 시 400, 성공 시 204(verifiedEmailCache 마커 등록). */
    @PostMapping("/email/verify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void verifyEmailOtp(@Valid @RequestBody EmailVerifyOtpRequest request) {
        emailVerificationService.verifyOtp(request.email(), request.code());
    }

    // ── 이메일 인증 ────────────────────────────────────────────────────────

    /** 이메일 회원가입. 성공 시 201 Created + 토큰 쌍 반환. */
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse signup(@Valid @RequestBody SignupRequest request) {
        return authService.signup(request);
    }

    /** 이메일 로그인. 성공 시 200 OK + 토큰 쌍 반환. */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** access token 갱신 (refresh rotation). */
    @PostMapping("/refresh")
    public AuthResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /** 로그아웃 — refresh token DB 삭제. 204 No Content. */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.refreshToken());
    }

    // ── OAuth 인증 ─────────────────────────────────────────────────────────

    /**
     * OAuth 인가 URL 반환 — state UUID 생성 후 캐시 저장.
     * provider: kakao / google / naver
     */
    @GetMapping("/oauth/{provider}/url")
    public OAuthUrlResponse getOAuthUrl(@PathVariable String provider) {
        return authService.getOAuthAuthorizationUrl(provider);
    }

    /**
     * OAuth 콜백 — 인가 코드 교환 → 사용자 정보 조회 → 로그인 또는 자동 회원가입 → JWT 발급.
     * 200 OK + 토큰 쌍 반환.
     */
    @PostMapping("/oauth/{provider}/callback")
    public AuthResponse oauthCallback(@PathVariable String provider,
                                      @Valid @RequestBody OAuthCallbackRequest request) {
        return authService.oauthCallback(provider, request.code(), request.state());
    }
}
