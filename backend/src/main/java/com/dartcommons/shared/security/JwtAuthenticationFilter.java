package com.dartcommons.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/*
 * [목적] 매 요청마다 Authorization: Bearer <token>에서 JWT를 추출·검증하고 SecurityContext에 인증 정보를 설정.
 *       principal = userId(Long), authorities = [ROLE_{TIER}]. 유효하지 않은 토큰은 WARN 로그로 기록(R11).
 * [이유] JWT 서명 검증만으로 인증을 완료해 DB 조회 없이 인증 처리(성능 최적화).
 *       토큰이 없거나 유효하지 않으면 SecurityContext를 건드리지 않고 다음 필터로 통과
 *       → SecurityConfig의 requestMatchers 정책이 permitAll/authenticated를 결정.
 * [사이드 임팩트] access token 만료나 서명 불일치 시 401은 SecurityConfig의 authenticationEntryPoint가 처리.
 *               R11 WARN 로그: 토큰 원본 값·userId 미포함 — e.getMessage()(오류 종류) + request.getRequestURI()만 기록.
 *               로그 폭주 시 디스크 포화 주의 — 로그 레벨/회전 정책 확인.
 * [수정 시 고려사항] refresh token을 Authorization 헤더에 넣지 않도록 클라이언트 가이드 필요.
 *                  토큰 블랙리스트(access token 즉시 무효화) 필요 시 Redis 조회 추가 가능.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider jwtTokenProvider;

    public JwtAuthenticationFilter(JwtTokenProvider jwtTokenProvider) {
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null) {
            try {
                Claims claims = jwtTokenProvider.parseClaims(token);
                Long userId   = Long.parseLong(claims.getSubject());
                String tier   = claims.get("tier", String.class);

                UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                        userId,
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + tier))
                );
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (JwtException | IllegalArgumentException e) {
                // R11: 토큰 원본 미포함 WARN 로그 — 공격 징후 모니터링. SecurityContext 미설정 → authenticated() 엔드포인트에서 401.
                log.warn("[JWT] Invalid token: {} path={}", e.getMessage(), request.getRequestURI());
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        // 1순위: Authorization: Bearer 헤더 (API 클라이언트, Swagger 등)
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        // 2순위: dr_session 쿠키 (Next.js httpOnly 쿠키 기반 브라우저 세션)
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            return Arrays.stream(cookies)
                    .filter(c -> "dr_session".equals(c.getName()))
                    .map(Cookie::getValue)
                    .filter(StringUtils::hasText)
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }
}
