package com.dartcommons.shared.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] /admin/** 인증용 운영자 계정 properties (HTTP Basic, in-memory user 1명).
 * [이유] MVP는 사용자 인증 도메인(user/, JWT) 미구현 상태 — BackfillController 등 관리자용 endpoint만
 *       단순 가드. 환경변수 주입으로 운영 토큰 회전 가능.
 * [사이드 임팩트] password는 평문 환경변수 — Spring Security가 BCrypt로 해시 후 보관(메모리만).
 *               운영 환경에서 ADMIN_PASSWORD 미설정 시 부팅 실패(@NotBlank).
 *               JWT 도입 시 본 properties 제거 + UserDetailsService 교체.
 * [수정 시 고려사항] username은 기본 'admin' — 필요 시 env로 변경. password는 강한 무작위(env 주입).
 *                  여러 운영자 계정이 필요하면 user 도메인 도입(Spec 분리).
 */
@ConfigurationProperties("dartcommons.admin")
@Validated
public record AdminAuthProperties(
        @DefaultValue("admin") @NotBlank String username,
        @NotBlank String password
) {
}
