package com.dartcommons.infrastructure.kakao;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] application.yml dartcommons.kakao.alimtalk.* 를 타입 안전 record로 바인딩하는 설정 객체.
 * [이유] CLAUDE.md §7: 카카오 알림톡 API 키 하드코딩 금지 — 환경변수 주입.
 *       DartApiProperties와 동일 패턴(@ConfigurationProperties + @Validated).
 * [사이드 임팩트] KakaoAlimtalkClient 빈이 이 properties에 직접 의존.
 *               apiKey/senderKey 미설정 시 애플리케이션 구동 실패(빠른 실패).
 * [수정 시 고려사항] base-url은 카카오 비즈메시지 가이드 확인 필요.
 *                  templateCode 변경 시 카카오 템플릿 심사 통과 필요.
 */
@ConfigurationProperties("dartcommons.kakao.alimtalk")
@Validated
public record KakaoAlimtalkProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotBlank String senderKey,
        @NotBlank String templateCode,
        @DefaultValue("10000") int timeoutMs,
        @DefaultValue("3") int maxRetries
) {
}
