package com.dartcommons.shared.config;

import com.dartcommons.shared.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
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
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/*
 * [목적] Spring Security 듀얼 FilterChain + CORS 화이트리스트 구성.
 *       @Order(1) AdminChain: /admin/** + /swagger-ui/** + /v3/api-docs/** — HTTP Basic + InMemoryUserDetailsManager.
 *       @Order(2) UserChain:  나머지 전체 — JWT Bearer + JwtAuthenticationFilter (사용자 인증).
 *       CorsConfigurationSource: ALLOWED_ORIGINS 환경변수로 허용 origin 제어 — 미설정 시 CORS 전면 차단(안전 실패).
 * [이유] admin과 user가 다른 인증 메커니즘 — 동일 FilterChain 충돌 방지.
 *       CORS 명시적 allowlist: 와일드카드 허용 금지 + allowCredentials(true) 동시 사용 금지(CORS 스펙).
 *       Swagger UI/API docs를 adminFilterChain으로 이관 — 운영 환경에서 springdoc 비활성화(application.yml)와 이중 방어.
 * [사이드 임팩트] ALLOWED_ORIGINS 미설정 → CORS preflight 403 — FE 로컬 개발 시 반드시 설정 필요.
 *               새 PUBLIC 엔드포인트 추가 시 userFilterChain.requestMatchers에 명시 필요.
 * [수정 시 고려사항] 레이트리밋(429)은 AOP/Bucket4j 레이어 별도 도입 필요.
 *                  WebSocket 도입 시 userFilterChain에 별도 matcher 추가.
 *                  멀티 인스턴스 환경에서 CORS 설정은 프록시(Nginx/API Gateway) 레벨로 이관 고려.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
                                                PasswordEncoder encoder,
                                                AdminAuthProperties props,
                                                @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        UserDetails admin = User.builder()
                .username(props.username())
                .password(encoder.encode(props.password()))
                .roles("ADMIN")
                .build();

        // R7: Swagger UI/API docs를 admin chain으로 이관 — HTTP Basic 인증 필요 (운영 환경: springdoc 자체를 비활성화)
        http.securityMatcher("/admin/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                .cors(c -> c.configurationSource(corsSource))
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
                                               JwtAuthenticationFilter jwtFilter,
                                               @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource) throws Exception {
        http.cors(c -> c.configurationSource(corsSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                // 미인증(no token / invalid token) → 401. 미인가(권한 부족) → 403 (Spring Security 기본).
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .authorizeHttpRequests(auth -> auth
                        // 헬스 체크
                        .requestMatchers("/actuator/health/**").permitAll()
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

    /**
     * R5: CORS 화이트리스트 — ALLOWED_ORIGINS 환경변수(콤마 구분)로 허용 origin 제어.
     * 미설정 시 emptyList → CORS preflight 403 (안전 실패). allowCredentials + 와일드카드 동시 사용 금지(CORS 스펙).
     * @Primary: mvcHandlerMappingIntrospector도 CorsConfigurationSource 구현 → @Qualifier로 명시적 주입 + @Primary로 기본 선택 지정.
     */
    @Bean
    @Primary
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${dartcommons.allowed-origins:}") String allowedOriginsStr) {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = StringUtils.hasText(allowedOriginsStr)
                ? Arrays.stream(allowedOriginsStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(Collectors.toList())
                : Collections.emptyList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
