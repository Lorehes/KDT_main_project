package com.dartcommons.shared.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/*
 * [목적] 매 요청마다 Authorization: Bearer <token>에서 JWT를 추출·검증하고 SecurityContext에 인증 정보를 설정.
 *       principal = userId(Long), authorities = [ROLE_{TIER}].
 * [이유] JWT 서명 검증만으로 인증을 완료해 DB 조회 없이 인증 처리(성능 최적화).
 *       토큰이 없거나 유효하지 않으면 SecurityContext를 건드리지 않고 다음 필터로 통과
 *       → SecurityConfig의 requestMatchers 정책이 permitAll/authenticated를 결정.
 * [사이드 임팩트] access token 만료나 서명 불일치 시 401은 SecurityConfig의 authenticationEntryPoint가 처리.
 *               refresh token rotation은 이 필터와 무관 — AuthController에서만 처리.
 * [수정 시 고려사항] refresh token을 Authorization 헤더에 넣지 않도록 클라이언트 가이드 필요.
 *                  토큰 블랙리스트(access token 즉시 무효화) 필요 시 Redis 조회 추가 가능.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

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
            } catch (JwtException | IllegalArgumentException ignored) {
                // 유효하지 않은 토큰 — SecurityContext 미설정 → authenticated() 엔드포인트에서 401
            }
        }
        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
