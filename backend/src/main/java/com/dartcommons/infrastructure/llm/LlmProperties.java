package com.dartcommons.infrastructure.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/*
 * [목적] LLM provider 설정 — application.yml의 dartcommons.llm.* 바인딩.
 * [이유] 모델/URL/타임아웃/API 키를 코드 하드코딩 금지(CLAUDE.md §7) — 환경변수 주입 + 운영 교체 가능.
 *       confidenceThreshold는 Stage 2 결과 신뢰도 임계치(결정 2: 0.6) — SystemConfig 보조 키 후속.
 * [사이드 임팩트] apiKey 필드 추가(llm-production-switch Spec) — OllamaLlmClient/MockLlmClient는 미사용,
 *               OpenRouterLlmClient만 Authorization 헤더에 주입. mock/ollama 시 빈 값("") 무해.
 *               record 컴포넌트 순서 변경 시 Spring 바인딩에는 영향 없으나, 직접 생성자 호출 테스트 있으면 컴파일 오류.
 *               현재 테스트는 @TestPropertySource 바인딩 방식만 사용 — 직접 new LlmProperties(...) 없음.
 * [수정 시 고려사항] provider 값 추가 시 대응 LlmClient 구현체 + HostWhitelist.PROD_ALLOWED 동시 추가 필수.
 *                  timeoutMs 기본값 60s(Ollama) → 30s(Cloud)로 변경(Cloud LLM은 응답 빠름).
 *                  Stage 3~5 전용 모델/타임아웃 필요 시 별도 @ConfigurationProperties 분리 검토.
 *                  stage2BodyMaxChars(stage2-body-in-prompt): Stage 2 프롬프트에 넣을 본문 발췌 상한.
 *                  content-max-chars(Stage 1 저장 5만자)와 별개 — 프롬프트 토큰/비용 균형용(결정: 6000).
 *                  @DefaultValue: @TestPropertySource 등 yml 키 미제공 환경에서도 record 바인딩 성공 보장.
 */
@ConfigurationProperties(prefix = "dartcommons.llm")
public record LlmProperties(
        String provider,            // "ollama" | "mock" | "openrouter"
        String baseUrl,             // Ollama: http://localhost:11434 / OpenRouter: https://openrouter.ai/api/v1
        String apiKey,              // OpenRouter API 키 — mock/ollama 시 빈 값 무해. 환경변수 주입 필수(CLAUDE.md §7)
        String model,               // 기본 google/gemma-3-4b-it:free (openrouter) / qwen3:4b (ollama)
        int timeoutMs,              // 기본 30000 (Cloud) / 60000 (Ollama)
        int maxRetries,             // 기본 2 (네트워크/타임아웃)
        double confidenceThreshold, // 기본 0.6 (결정 2) — 미만 시 is_withheld=true
        @DefaultValue("6000") int stage2BodyMaxChars // Stage 2 프롬프트 본문 발췌 상한(글자). 0이면 본문 미투입
) {
}
