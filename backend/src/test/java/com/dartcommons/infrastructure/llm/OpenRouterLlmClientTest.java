package com.dartcommons.infrastructure.llm;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.shared.enums.Sentiment;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.*;

/*
 * OpenRouterLlmClient 단위 테스트 — Spring context 없이 Java 내장 HttpServer로 실제 HTTP 동작 검증.
 * @Retryable(Spring AOP)은 단위 테스트 범위 밖 — 재시도 동작은 통합 테스트에서 별도 검증.
 * llm-production-switch Spec 테스트 전략: RestClientException 전파·JSON 파싱·apiKey 검증.
 */
class OpenRouterLlmClientTest {

    private HttpServer httpServer;
    private OpenRouterLlmClient client;

    @BeforeEach
    void setUp() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress(0), 0);
        httpServer.start();
        int port = httpServer.getAddress().getPort();
        LlmProperties props = new LlmProperties(
                "openrouter", "http://localhost:" + port, "test-key",
                "test-model", 5000, 1, 0.6);
        client = new OpenRouterLlmClient(props);
    }

    @AfterEach
    void tearDown() {
        httpServer.stop(0);
    }

    @Test
    void classifyStage2_정상응답_Stage2Output반환() {
        String responseJson = """
                {"id":"gen-1","choices":[{"message":{"content":
                "{\\"sentiment\\":\\"POSITIVE\\",\\"confidence\\":0.85,\\"summary\\":\\"공시 호재\\"}"}}]}
                """;
        httpServer.createContext("/chat/completions", exchange -> respond(exchange, 200, responseJson));

        Stage2Output result = client.classifyStage2("test prompt");

        assertThat(result.sentiment()).isEqualTo(Sentiment.POSITIVE);
        assertThat(result.confidence()).isEqualByComparingTo("0.850");
        assertThat(result.summary()).isEqualTo("공시 호재");
    }

    @Test
    void classifyStage2_추가필드포함JSON_파싱성공_FAIL_ON_UNKNOWN검증() {
        // H1 수정 검증: LLM이 reasoning 등 추가 필드를 포함해도 FAIL_ON_UNKNOWN_PROPERTIES=false로 성공
        String responseJson = """
                {"choices":[{"message":{"content":
                "{\\"sentiment\\":\\"NEGATIVE\\",\\"confidence\\":0.9,\\"summary\\":\\"악재\\",\\"reasoning\\":\\"추가설명\\"}"}}]}
                """;
        httpServer.createContext("/chat/completions", exchange -> respond(exchange, 200, responseJson));

        Stage2Output result = client.classifyStage2("test prompt");

        assertThat(result.sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(result.confidence()).isEqualByComparingTo("0.900");
    }

    @Test
    void classifyStage2_HTTP401_RestClientException발생() {
        httpServer.createContext("/chat/completions", exchange -> respond(exchange, 401, "Unauthorized"));

        assertThatThrownBy(() -> client.classifyStage2("test prompt"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("401");
    }

    @Test
    void classifyStage2_HTTP500_RestClientException발생() {
        httpServer.createContext("/chat/completions", exchange -> respond(exchange, 500, "Internal Server Error"));

        assertThatThrownBy(() -> client.classifyStage2("test prompt"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("500");
    }

    @Test
    void classifyStage2_깨진JSON_RestClientException발생() {
        String responseJson = """
                {"choices":[{"message":{"content":"NOT_JSON"}}]}
                """;
        httpServer.createContext("/chat/completions", exchange -> respond(exchange, 200, responseJson));

        assertThatThrownBy(() -> client.classifyStage2("test prompt"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("JSON 파싱 실패");
    }

    @Test
    void classifyStage2_잘못된sentiment값_RestClientException발생() {
        String responseJson = """
                {"choices":[{"message":{"content":
                "{\\"sentiment\\":\\"UNKNOWN_VALUE\\",\\"confidence\\":0.7,\\"summary\\":\\"요약\\"}"}}]}
                """;
        httpServer.createContext("/chat/completions", exchange -> respond(exchange, 200, responseJson));

        assertThatThrownBy(() -> client.classifyStage2("test prompt"))
                .isInstanceOf(RestClientException.class)
                .hasMessageContaining("JSON 파싱 실패");
    }

    @Test
    void 생성자_apiKey_빈값_IllegalStateException발생() {
        // M1 수정 검증: apiKey 공백 시 부팅 시점에 즉시 실패 (재시도 낭비 방지)
        LlmProperties blankKeyProps = new LlmProperties(
                "openrouter", "http://localhost:1", "", "test-model", 5000, 1, 0.6);

        assertThatThrownBy(() -> new OpenRouterLlmClient(blankKeyProps))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OPENROUTER_API_KEY");
    }

    // ---- helpers ----

    private void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) {
        try {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.getResponseBody().close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
