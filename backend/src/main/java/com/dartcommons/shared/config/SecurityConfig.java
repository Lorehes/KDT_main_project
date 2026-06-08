package com.dartcommons.shared.config;

import com.dartcommons.shared.security.JwtAuthenticationFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/*
 * [목적] Spring Security 듀얼 FilterChain 구성.
 *       @Order(1) AdminChain: /admin/**  — HTTP Basic + InMemoryUserDetailsManager (운영자 전용).
 *       @Order(2) UserChain:  나머지 전체 — JWT Bearer + JwtAuthenticationFilter (사용자 인증).
 * [이유] admin(운영자)과 user(일반 사용자)가 다른 인증 메커니즘을 사용 — 동일 FilterChain에 두면 충돌.
 *       securityMatcher("/admin/**")로 admin 체인을 격리해 /api/v1/**가 user 체인만 거치도록.
 *       http.userDetailsService()로 각 체인에 독립적인 UserDetailsService를 바인딩(Spring Security 6.4+).
 * [사이드 임팩트] user 체인의 JwtAuthenticationFilter는 모든 비-admin 요청에 실행.
 *               permitAll 경로(auth/**, stocks/search 등)는 토큰 없어도 통과 — 필터는 실행되지만 ctx 미설정으로 무해.
 *               새 PUBLIC 엔드포인트 추가 시 userFilterChain의 requestMatchers에 명시 필요.
 * [수정 시 고려사항] CORS 설정은 프론트엔드 도메인 확정 후 http.cors() 추가 필요.
 *                  레이트리밋(429 Retry-After)은 AOP/Bucket4j 레이어 별도 도입 필요.
 *                  WebSocket 도입 시 userFilterChain에 별도 matcher 추가.
 *                  미인증(401) vs 미인가(403) 구분: exceptionHandling.authenticationEntryPoint(HttpStatusEntryPoint(UNAUTHORIZED)).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                PasswordEncoder encoder,
                                                AdminAuthProperties props) throws Exception {
        UserDetails admin = User.builder()
                .username(props.username())
                .password(encoder.encode(props.password()))
                .roles("ADMIN")
                .build();

        http.securityMatcher("/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .userDetailsService(new InMemoryUserDetailsManager(admin))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    @Bean
    @Order(2)
    public SecurityFilterChain userFilterChain(HttpSecurity http,
                                               JwtAuthenticationFilter jwtFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // 미인증(no token / invalid token) → 401. 미인가(권한 부족) → 403 (Spring Security 기본).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        // 문서·헬스
                        .requestMatchers(
                                "/actuator/health/**",
                                "/v3/api-docs/**",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        // 공개 API — 인증 불필요
                        .requestMatchers(
                                "/api/v1/auth/**",
                                "/api/v1/stocks/search",
                                "/api/v1/pricing/**"
                        ).permitAll()
                        // 나머지는 JWT 인증 필요
                        .anyRequest().authenticated()
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
