package com.dartcommons.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/*
 * [목적] Spring Security 설정 — /admin/**만 HTTP Basic 인증, 나머지 permitAll.
 *       MVP 단순 가드: BackfillController 등 운영자 endpoint 보호.
 * [이유] BackfillController는 DART 65k+ 호출을 트리거 가능 — 무방비 노출 시 의도되지 않은 대량 호출/비용.
 *       사용자 도메인(user/) 미구현 상태라 in-memory user + HTTP Basic으로 최소 보호(통합기획서 §11).
 * [사이드 임팩트] 세션 미사용(STATELESS) — Basic 인증마다 자격 검증. CSRF 비활성(REST API).
 *               /actuator/health, /v3/api-docs, /swagger-ui/** 등 공개 endpoint는 permitAll.
 *               WebSocket·정적 자원 도입 시 SecurityFilterChain에 추가 매칭 필요.
 * [수정 시 고려사항] JWT 도입 시 본 설정의 InMemoryUserDetailsManager → DB 기반 UserDetailsService 교체.
 *                  운영 환경 ADMIN_PASSWORD는 강한 무작위(32자+) 환경변수, secret manager 연동 권장.
 *                  /admin/** 외 새 관리 endpoint 추가 시 자동으로 보호됨(prefix 규약).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 공개 — 공시 통역 서비스의 사용자 라우트 + 헬스/문서
                        .requestMatchers(
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // 관리자 — 백필 등 운영자 트리거
                        .requestMatchers("/admin/**").authenticated()
                        // 그 외는 permitAll — 사용자 인증 도메인 도입 시 별도 SecurityFilterChain 분리
                        .anyRequest().permitAll()
                )
                .httpBasic(basic -> {
                });
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public UserDetailsService userDetailsService(AdminAuthProperties props, PasswordEncoder encoder) {
        UserDetails admin = User.builder()
                .username(props.username())
                .password(encoder.encode(props.password()))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }
}
