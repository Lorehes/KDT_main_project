package com.dartcommons.infrastructure.llm;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.analysis.entities.AnalysisResult.Sentiment;
import com.dartcommons.shared.util.HostWhitelist;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/*
 * [목적] Ollama HTTP API(/api/generate)로 LLM 분류를 호출하는 MVP 어댑터.
 *       provider=ollama 일 때 활성화되어 LlmClient 구현체로 주입된다.
 * [이유] LangChain4j 의존성 추가 대신 직접 RestClient 호출 — qwen3의 think=false 같은 raw 옵션
 *       100% 컨트롤(LangChain4j가 모든 ollama 옵션을 노출하지 않을 수 있음). DartClient와 동일 패턴.
 *       2026-06-04 smoke test: qwen3:4b는 thinking 모드가 기본이라 format=json 시 무한 thinking → 빈 응답.
 *       think=false 필수.
 * [사이드 임팩트] 외부 호출 — application.yml dartcommons.llm.timeout-ms / max-retries / model 영향.
 *               @Retryable 2회는 네트워크/타임아웃 한정 — JSON 파싱 실패는 호출 측 Stage2Analyzer 책임.
 *               응답 텍스트는 JSON 문자열(format=json 보장) — record 매핑 실패 시 RestClientException 변환.
 * [수정 시 고려사항] Stage 3+ RAG 도입 시 langchain4j-chroma 검토. 그때 본 클라이언트는 유지 또는 교체.
 *                  Cloud 어댑터(OpenAI/Anthropic) 추가 시 본 클라이언트와 동일 인터페이스로 분기.
 *                  qwen3 외 모델 사용 시 think 옵션 미지원 — application.yml로 옵션 토글 가능하게 후속 검토.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.llm", name = "provider", havingValue = "ollama")
public class OllamaLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OllamaLlmClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final LlmProperties props;

    public OllamaLlmClient(LlmProperties props) {
        HostWhitelist.verify(props.baseUrl(), "OllamaLlmClient");
        this.props = props;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMs()))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultStatusHandler(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new RestClientException("Ollama HTTP error: " + res.getStatusCode());
                        })
                .build();
    }

    /*
     * maxAttempts=3 = 초기 호출 + 2회 재시도 (LlmProperties.maxRetries 기본 2와 정합).
     * SpEL bean 참조(#{@llmProperties.maxRetries()+1})는 Spring Boot @ConfigurationProperties 빈명 규칙 차이로
     * 'llmProperties' 이름 해석 실패함이 운영 확인됨(2026-06-05). 상수로 고정하고 변경 시 함께 갱신.
     */
    @Override
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8_000)
    )
    public Stage2Output classifyStage2(String prompt) {
        // think=false 가 핵심 — qwen3 thinking 모드는 format=json과 충돌해 빈 응답 발생(smoke test 검증).
        // num_predict=400 은 3줄 요약 + JSON 키 + sentiment/confidence 총량 여유.
        // temperature=0.2 는 결정론적 분류 유도 (창의성 억제).
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "prompt", prompt,
                "format", "json",
                "stream", false,
                "think", false,
                "options", Map.of(
                        "temperature", 0.2,
                        "num_predict", 400
                )
        );

        OllamaGenerateResponse res = restClient.post()
                .uri("/api/generate")
                .body(body)
                .retrieve()
                .body(OllamaGenerateResponse.class);

        if (res == null || res.response() == null || res.response().isBlank()) {
            throw new RestClientException("Ollama 빈 응답 — think 옵션 또는 모델 호환성 확인");
        }

        try {
            Stage2OutputRaw raw = MAPPER.readValue(res.response(), Stage2OutputRaw.class);
            return raw.toStage2Output();
        } catch (Exception e) {
            log.warn("Ollama JSON 파싱 실패 — raw={}, error={}", res.response(), e.getMessage());
            // 파싱 실패는 재시도 가치 낮음(같은 LLM이 같은 프롬프트에 같은 깨진 응답) — 호출 측이 1회 재호출 결정.
            throw new RestClientException("Ollama JSON 파싱 실패: " + e.getMessage());
        }
    }

    /*
     * Ollama /api/generate 응답 — format=json 시 response 필드가 JSON 문자열.
     * eval_count/total_duration은 토큰 추적(input/output_tokens 영속화는 후속).
     */
    private record OllamaGenerateResponse(
            String model,
            String response,
            boolean done,
            @JsonProperty("done_reason") String doneReason,
            @JsonProperty("total_duration") Long totalDuration,
            @JsonProperty("eval_count") Integer evalCount,
            @JsonProperty("prompt_eval_count") Integer promptEvalCount
    ) {
    }

    /*
     * LLM이 생성한 JSON을 record로 강제 파싱 — 환각/스키마 불일치 차단(CLAUDE.md §6-6).
     * sentiment 대소문자/공백 정규화, confidence 범위 클램프는 toStage2Output에서.
     */
    private record Stage2OutputRaw(
            String sentiment,
            BigDecimal confidence,
            String summary
    ) {
        Stage2Output toStage2Output() {
            Sentiment s = parseSentiment(sentiment);
            BigDecimal c = clampConfidence(confidence);
            String sum = summary == null ? "" : summary.trim();
            return new Stage2Output(s, c, sum);
        }

        private static Sentiment parseSentiment(String raw) {
            if (raw == null) throw new IllegalArgumentException("sentiment is null");
            return Sentiment.valueOf(raw.trim().toUpperCase());
        }

        private static BigDecimal clampConfidence(BigDecimal raw) {
            if (raw == null) throw new IllegalArgumentException("confidence is null");
            if (raw.compareTo(BigDecimal.ZERO) < 0) return BigDecimal.ZERO;
            if (raw.compareTo(BigDecimal.ONE) > 0) return BigDecimal.ONE;
            return raw.setScale(3, java.math.RoundingMode.HALF_UP);
        }
    }
}
