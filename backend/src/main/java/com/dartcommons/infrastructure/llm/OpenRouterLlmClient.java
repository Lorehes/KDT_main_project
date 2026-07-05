package com.dartcommons.infrastructure.llm;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.analysis.dto.Stage4Output;
import com.dartcommons.analysis.entities.AnalysisResult.ExpectedReaction;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.shared.util.HostWhitelist;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/*
 * [목적] OpenRouter Cloud LLM API(/chat/completions)로 Stage 2 분류를 호출하는 프로덕션 어댑터.
 *       provider=openrouter 일 때 활성화. OllamaLlmClient와 동일한 LlmClient 인터페이스 구현.
 * [이유] OpenRouter는 OpenAI Chat Completions 호환 포맷을 제공하는 LLM 라우터.
 *       단일 API 키로 Gemma·LLaMA·Claude·Gemini 등 수십 개 모델을 model명 교체만으로 전환 가능.
 *       서버에 Ollama를 설치할 필요 없어 EC2 t3.medium(4GB)으로 배포 가능 — 서버 비용 절감.
 *       OllamaLlmClient와 동일 패턴(HostWhitelist·RestClient·@Retryable·Stage2OutputRaw)을 답습해
 *       코드 일관성 유지 및 향후 어댑터 추가 시 참조 패턴 제공.
 * [사이드 임팩트] application.yml dartcommons.llm.provider=openrouter 설정 시 OllamaLlmClient 대신 본 빈 주입.
 *               OPENROUTER_API_KEY 미설정(빈 값) 시 생성자에서 IllegalStateException → 부팅 실패(의도된 빠른 실패).
 *               analysis_results.model_name에 OpenRouter 모델명(google/gemma-3-4b-it:free 등) 저장됨.
 *               PromptGuard(L2)는 OpenRouter 모델 출력에도 동일 적용 — 투자 권유 표현 차단 유지.
 * [수정 시 고려사항] 모델 교체는 LLM_MODEL 환경변수만 변경(코드 무수정). 무료→유료 전환 동일.
 *                  Rate Limit(무료 모델) 초과 시 429 응답 → RestClientException → @Retryable 재시도.
 *                  429가 지속되면 backoff가 최대 8초까지 증가하나 해소 보장 없음 — 유료 모델 전환 고려.
 *                  OpenRouter 모델이 response_format=json_object를 미지원할 경우 JSON 파싱 실패 가능성 존재.
 *                  Stage 3~5 도입 시 별도 메서드 또는 별도 클라이언트(classifyStage4 등) 분리 검토.
 *                  CONNECT_TIMEOUT_MS(5s)는 Cloud LLM TCP 연결 기준. readTimeout은 props.timeoutMs()(30s) 유지.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.llm", name = "provider", havingValue = "openrouter")
public class OpenRouterLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenRouterLlmClient.class);
    // FAIL_ON_UNKNOWN_PROPERTIES=false: LLM이 reasoning 등 추가 필드를 출력해도 파싱 실패 방지
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    // Cloud LLM은 TCP 연결이 빠름 — connectTimeout을 짧게, readTimeout은 별도(props.timeoutMs)로 유지
    private static final int CONNECT_TIMEOUT_MS = 5_000;

    private final RestClient restClient;
    private final LlmProperties props;

    public OpenRouterLlmClient(LlmProperties props) {
        HostWhitelist.verify(props.baseUrl(), "OpenRouterLlmClient");
        // provider=openrouter인데 apiKey 미설정 시 재시도 3회(최대 ~90s) 낭비를 부팅 시 즉시 차단
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "OpenRouterLlmClient: dartcommons.llm.api-key가 비어 있습니다. OPENROUTER_API_KEY 환경변수를 설정하세요.");
        }
        this.props = props;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS))
                .build();
        var factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                .defaultStatusHandler(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new RestClientException(
                                    "OpenRouter HTTP error: " + res.getStatusCode() +
                                    " — model=" + props.model());
                        })
                .build();
    }

    /*
     * OllamaLlmClient와 동일한 @Retryable 설정.
     * maxAttempts=3 = 초기 호출 + 2회 재시도. SpEL bean 참조 실패 이슈(2026-06-05 확인)로 상수 고정.
     * OpenRouter 429(Rate Limit)도 RestClientException으로 변환되므로 자동 재시도 대상.
     */
    @Override
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8_000)
    )
    public Stage2Output classifyStage2(String prompt) {
        // response_format=json_object: OpenRouter가 JSON만 출력하도록 강제.
        // temperature=0.2: 결정론적 분류 유도 (창의성 억제) — OllamaLlmClient와 동일.
        // max_tokens=800: key_points + 호재/악재 요인 리스트 출력 총량(OllamaLlmClient와 정합).
        // stage2-body-in-prompt: 프롬프트에 본문 발췌(~6000자)가 포함되면 입력 토큰이 공시당 수천 토큰 증가 →
        //   OpenRouter는 입력 토큰당 과금이므로 월 공시량 × 본문 토큰만큼 비용↑. Cloud 모델은 컨텍스트가 커
        //   Ollama의 num_ctx 같은 별도 설정은 불필요. readTimeout(props.timeoutMs)은 긴 입력에도 30s로 충분(응답 빠름).
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "messages", List.of(
                        Map.of("role", "user", "content", prompt)
                ),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.2,
                "max_tokens", 800
        );

        OpenRouterResponse res = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(OpenRouterResponse.class);

        if (res == null || res.choices() == null || res.choices().isEmpty()) {
            throw new RestClientException("OpenRouter 빈 응답 — choices 없음, model=" + props.model());
        }

        String content = res.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            throw new RestClientException("OpenRouter choices[0].message.content 빈 값, model=" + props.model());
        }

        try {
            Stage2OutputRaw raw = MAPPER.readValue(content, Stage2OutputRaw.class);
            return raw.toStage2Output();
        } catch (Exception e) {
            // 로그 볼륨 제어: LLM 응답이 길 수 있으므로 500자로 잘라 출력
            String preview = content.length() > 500 ? content.substring(0, 500) + "…" : content;
            log.warn("OpenRouter JSON 파싱 실패 — content={}, error={}", preview, e.getMessage());
            throw new RestClientException("OpenRouter JSON 파싱 실패: " + e.getMessage());
        }
    }

    @Override
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8_000)
    )
    public Stage4Output classifyStage4(String prompt) {
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "messages", List.of(Map.of("role", "user", "content", prompt)),
                "response_format", Map.of("type", "json_object"),
                "temperature", 0.2,
                "max_tokens", 400
        );

        OpenRouterResponse res = restClient.post()
                .uri("/chat/completions")
                .body(body)
                .retrieve()
                .body(OpenRouterResponse.class);

        if (res == null || res.choices() == null || res.choices().isEmpty()) {
            throw new RestClientException("OpenRouter Stage4 빈 응답 — choices 없음");
        }
        String content = res.choices().get(0).message().content();
        if (content == null || content.isBlank()) {
            throw new RestClientException("OpenRouter Stage4 choices[0].message.content 빈 값");
        }
        try {
            Stage4OutputRaw raw = MAPPER.readValue(content, Stage4OutputRaw.class);
            return raw.toStage4Output();
        } catch (Exception e) {
            String preview = content.length() > 500 ? content.substring(0, 500) + "…" : content;
            log.warn("OpenRouter Stage4 JSON 파싱 실패 — content={}, error={}", preview, e.getMessage());
            throw new RestClientException("OpenRouter Stage4 JSON 파싱 실패: " + e.getMessage());
        }
    }

    private record Stage4OutputRaw(
            @com.fasterxml.jackson.annotation.JsonProperty("expected_reaction") String expectedReaction,
            String rationale,
            BigDecimal confidence
    ) {
        Stage4Output toStage4Output() {
            if (expectedReaction == null) throw new IllegalArgumentException("expected_reaction is null");
            ExpectedReaction er = ExpectedReaction.valueOf(expectedReaction.trim().toUpperCase());
            String r = rationale == null ? "" : rationale.trim();
            BigDecimal c = confidence == null ? new BigDecimal("0.500")
                    : confidence.max(BigDecimal.ZERO).min(BigDecimal.ONE).setScale(3, java.math.RoundingMode.HALF_UP);
            return new Stage4Output(er, r, c);
        }
    }

    // OpenAI Chat Completions 응답 구조 — OpenRouter가 동일 포맷 사용.
    // usage·finish_reason·role 등 미사용 필드는 FAIL_ON_UNKNOWN_PROPERTIES=false로 자동 무시.
    // 후속 wave에서 usage(토큰 집계) 영속화 시 필드 추가.
    private record OpenRouterResponse(
            String id,
            List<Choice> choices
    ) {}

    private record Choice(Message message) {}

    private record Message(String content) {}

    /*
     * OllamaLlmClient.Stage2OutputRaw와 동일 로직 — LLM 출력 JSON을 record로 강제 파싱(환각 차단).
     * 두 클라이언트가 독립 private record로 각자 보유: 공유 클래스화 시 패키지 노출 증가,
     * 두 파일만 존재하므로 DRY 비용보다 캡슐화 이득이 큼.
     */
    private record Stage2OutputRaw(
            String sentiment,
            BigDecimal confidence,
            String summary,
            @JsonProperty("key_points") List<String> keyPoints,
            @JsonProperty("positive_factors") List<String> positiveFactors,
            @JsonProperty("negative_factors") List<String> negativeFactors
    ) {
        Stage2Output toStage2Output() {
            Sentiment s = parseSentiment(sentiment);
            BigDecimal c = clampConfidence(confidence);
            String sum = summary == null ? "" : summary.trim();
            return new Stage2Output(s, c, sum,
                    normalizeList(keyPoints), normalizeList(positiveFactors), normalizeList(negativeFactors));
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

        /** null/공백 항목 제거 후 불변 리스트 — OllamaLlmClient와 동일 로직. */
        private static List<String> normalizeList(List<String> raw) {
            if (raw == null) return List.of();
            return raw.stream().filter(x -> x != null && !x.isBlank()).map(String::trim).toList();
        }
    }
}
