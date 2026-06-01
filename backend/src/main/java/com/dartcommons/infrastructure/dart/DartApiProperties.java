package com.dartcommons.infrastructure.dart;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] application.yml dartcommons.dart.* 를 타입 안전 record로 바인딩하는 설정 객체.
 * [이유] DART API 키를 환경변수로만 주입하는 CLAUDE.md §7 규칙 준수.
 *       하드코딩 방지와 테스트 환경별 오버라이드 지원.
 * [사이드 임팩트] DartClient 빈이 이 properties에 직접 의존. apiKey가 공백이면 DART status=020 반환.
 *               @Validated + @NotBlank로 빈 apiKey/baseUrl 시 애플리케이션 구동 실패(빠른 실패).
 * [수정 시 고려사항] base-url 변경 없이 api-key만 교체하면 운영 키 롤링 가능.
 *                  timeout-ms / max-retries는 DART rate limit(일 20,000호출)을 초과하지 않는 선에서 조정.
 */
@ConfigurationProperties("dartcommons.dart")
@Validated
public record DartApiProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @DefaultValue("10000") int timeoutMs,
        @DefaultValue("3") int maxRetries
) {
}
