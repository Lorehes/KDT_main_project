package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.AuthResponse;
import com.dartcommons.user.dto.LoginRequest;
import com.dartcommons.user.dto.RefreshRequest;
import com.dartcommons.user.dto.SignupRequest;
import com.dartcommons.user.services.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 이메일 인증 REST 엔드포인트 — signup / login / refresh / logout.
 *       /api/v1/auth/** 는 SecurityConfig userFilterChain에서 permitAll (토큰 없이 접근 가능).
 * [이유] 회원가입·로그인은 공개 엔드포인트. 갱신·로그아웃은 raw refresh token을 body로 수신.
 *       access token이 없어도 refresh token만 있으면 갱신 가능해야 함(UX).
 * [사이드 임팩트] /api/v1/auth/** permitAll 정책으로 JWT 없어도 도달 — AuthService가 검증 책임.
 *               logout 응답은 204(No Content) — refresh token 무효화만 수행.
 * [수정 시 고려사항] OAuth2 엔드포인트(GET /auth/oauth/{provider}/url, POST /auth/oauth/{provider}/callback)는
 *               Wave 4에서 이 컨트롤러에 추가. logout을 HttpOnly Cookie로 전환 시 @CookieValue 방식.
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

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
}
