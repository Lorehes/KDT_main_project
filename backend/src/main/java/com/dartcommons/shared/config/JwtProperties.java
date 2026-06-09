package com.dartcommons.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] JWT access/refresh 토큰 발급 설정 — secret·TTL을 환경변수로 주입(CLAUDE.md §7).
 * [이유] JWT_SECRET 하드코딩 금지. @ConfigurationPropertiesScan이 자동 발견(DartcommonsApplication).
 *       AdminAuthProperties와 동일한 record 패턴 적용(기존 프로젝트 컨벤션).
 *       secret은 Base64 인코딩된 값으로 전달 — JwtTokenProvider가 디코딩해 키 생성.
 *       Base64(32 bytes) = 44 chars 이상, @Size(min=44)로 구문 수준 사전 검증.
 * [사이드 임팩트] JWT_SECRET 미설정 시 @NotBlank → 부팅 즉시 실패(안전 실패).
 *               R14 파괴적 변경: 기존 raw string secret은 Base64 디코딩 실패 → IllegalStateException 부팅 실패.
 *               기존 발급 access token 전부 무효화됨(JWT_SECRET 변경과 동일) — 사용자 재로그인 1회 필요.
 *               accessTtlMinutes/refreshTtlDays 변경은 기존 발급 토큰에 소급 적용 안 됨.
 * [수정 시 고려사항] 신규 secret 생성: `openssl rand -base64 32` (32 random bytes → 44-char Base64).
 *                  key rotation 필요 시 JwtTokenProvider에 kid(key id) 확장 검토.
 */
@ConfigurationProperties("dartcommons.jwt")
@Validated
public record JwtProperties(
        // Base64로 인코딩된 HMAC-SHA256 키. 생성: openssl rand -base64 32 (32 bytes → 44 chars)
        @NotBlank @Size(min = 44, message = "JWT_SECRET must be Base64-encoded bytes of at least 32 bytes (use: openssl rand -base64 32)")
        String secret,
        @DefaultValue("30") @Positive int accessTtlMinutes,
        @DefaultValue("14") @Positive int refreshTtlDays
) {
}
