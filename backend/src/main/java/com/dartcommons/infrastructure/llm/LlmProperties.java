package com.dartcommons.infrastructure.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * [목적] LLM provider 설정 — application.yml의 dartcommons.llm.* 바인딩.
 * [이유] 모델/URL/타임아웃을 코드 하드코딩 금지(CLAUDE.md §7) — 환경변수 주입 + 운영 교체 가능.
 *       confidenceThreshold는 Stage 2 결과 신뢰도 임계치(결정 2: 0.6) — SystemConfig 보조 키 후속.
 * [사이드 임팩트] 본 클래스는 메인 부트스트랩에서 @ConfigurationPropertiesScan 또는
 *               @EnableConfigurationProperties로 등록되어야 활성화.
 * [수정 시 고려사항] provider 값 추가 시 OllamaLlmClient/MockLlmClient 외 어댑터 분기 필요.
 *                  timeoutMs는 LLM 응답 가변성 고려 60s 기본(DART 10s보다 큼).
 */
@ConfigurationProperties(prefix = "dartcommons.llm")
public record LlmProperties(
        String provider,            // "ollama" | "mock" (wave 2~ "openai" 등)
        String baseUrl,             // Ollama: http://localhost:11434
        String model,               // 기본 qwen2.5:7b-instruct (결정 4)
        int timeoutMs,              // 기본 60000
        int maxRetries,             // 기본 2 (네트워크/타임아웃)
        double confidenceThreshold  // 기본 0.6 (결정 2) — 미만 시 is_withheld=true
) {
}
