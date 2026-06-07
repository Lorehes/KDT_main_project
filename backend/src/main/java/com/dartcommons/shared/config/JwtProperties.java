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
 * [사이드 임팩트] JWT_SECRET 미설정 시 @NotBlank → 부팅 즉시 실패(안전 실패).
 *               accessTtlMinutes/refreshTtlDays 변경은 기존 발급 토큰에 소급 적용 안 됨.
 * [수정 시 고려사항] secret을 HMAC-256 키로 사용 — 최소 32바이트(256비트) 이상 권장.
 *                  key rotation(키 교체) 필요 시 JwtTokenProvider에 kid(key id) 확장 검토.
 */
@ConfigurationProperties("dartcommons.jwt")
@Validated
public record JwtProperties(
        @NotBlank @Size(min = 32, message = "JWT_SECRET must be at least 32 characters (256 bits for HMAC-SHA256)")
        String secret,
        @DefaultValue("30") @Positive int accessTtlMinutes,
        @DefaultValue("14") @Positive int refreshTtlDays
) {
}
