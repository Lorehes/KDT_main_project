package com.dartcommons.infrastructure.telegram;

import com.dartcommons.shared.util.HostWhitelist;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

/*
 * [목적] 텔레그램 Bot API 인프라 클라이언트 — 알림 발송(sendMessage) + 연동 메시지 수거(getUpdates).
 *       RestClient(Spring 6.1+) + @Retryable 백오프 패턴(KakaoAlimtalkClient·DartClient와 동일).
 * [이유] infrastructure/telegram으로 격리해 notification·user 도메인이 Bot API 세부사항에 의존하지 않도록 함
 *       (CLAUDE.md §3-2, feature_structure §6).
 * [사이드 임팩트] HostWhitelist에 api.telegram.org 등재 필수 — 누락 시 부팅 실패(의도된 빠른 실패).
 *               봇 토큰이 URL 경로에 포함되는 텔레그램 API 특성상, 이 클래스의 예외/로그에 URI를 절대 싣지 않는다.
 *               403(사용자가 봇 차단)은 TelegramForbiddenException으로 분리 — @Retryable 대상 아님(영구 실패),
 *               ChannelSender가 잡아 chat_id 해제 + FAILED 종결 처리.
 * [수정 시 고려사항] 봇 토큰 유출 시 BotFather /revoke 후 TELEGRAM_BOT_TOKEN만 교체.
 *                  polling(getUpdates)과 webhook은 상호 배타 — 봇에 webhook을 설정하면 폴링이 409로 실패.
 *                  대량 발송 시 초당 ~30건 제한 — 429는 RestClientException으로 재시도(백오프) 흡수,
 *                  사용자 규모 확대 시 발송 큐 스로틀 도입(Spec 후속 이슈).
 */
@Component
public class TelegramClient {

    private static final Logger log = LoggerFactory.getLogger(TelegramClient.class);

    private final RestClient restClient;
    private final TelegramProperties props;

    /** 사용자가 봇을 차단(403 Forbidden)한 영구 실패 — 재시도 무의미, chat_id 해제 트리거. */
    public static class TelegramForbiddenException extends RuntimeException {
        public TelegramForbiddenException(String message) {
            super(message);
        }
    }

    public TelegramClient(TelegramProperties props) {
        HostWhitelist.verify(props.baseUrl(), "TelegramClient");
        this.props = props;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMs()))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultStatusHandler(
                        status -> status.value() == 403,
                        (req, res) -> {
                            // 토큰이 URL에 포함 — req.getURI() 절대 미포함(시크릿 마스킹)
                            throw new TelegramForbiddenException("Telegram 403 Forbidden (bot blocked by user)");
                        })
                .defaultStatusHandler(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new RestClientException("Telegram HTTP error: " + res.getStatusCode());
                        })
                .build();
    }

    public boolean isDevMode() {
        return props.isDevMode();
    }

    /**
     * 알림 단건 발송. parse_mode=HTML — 본문의 사용자 데이터는 조립 측(NotificationMessageBuilder)에서
     * HTML 이스케이프 완료 상태여야 한다. 성공 시 true, 재시도 소진 시 RestClientException,
     * 봇 차단 시 TelegramForbiddenException throw.
     */
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10_000)
    )
    public boolean send(String chatId, String htmlBody) {
        if (chatId == null || chatId.isBlank()) {
            throw new IllegalArgumentException("chatId must not be blank");
        }
        if (htmlBody == null || htmlBody.isBlank()) {
            throw new IllegalArgumentException("htmlBody must not be blank");
        }
        // 개발 환경 placeholder 모드 — 실제 API 호출 없이 로그만 기록. chat_id 평문 로깅 금지.
        if (isDevMode()) {
            log.info("[DEV] Telegram send SKIP (placeholder mode) chatId=[REDACTED]");
            return true;
        }
        log.debug("Telegram send attempt: chatId=[REDACTED]");
        restClient.post()
                .uri("/bot{token}/sendMessage", props.botToken())
                .body(new SendMessageRequest(chatId, htmlBody, "HTML", true))
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    /**
     * 연동용 업데이트 수거 — offset 이후의 봇 수신 메시지(/start 토큰 등) 조회.
     * TelegramLinkPollingJob 전용. dev 모드면 빈 리스트 반환.
     */
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 2,
            backoff = @Backoff(delay = 1000)
    )
    public List<Update> getUpdates(long offset) {
        if (isDevMode()) {
            return List.of();
        }
        UpdatesResponse response = restClient.get()
                .uri("/bot{token}/getUpdates?offset={offset}&timeout=0", props.botToken(), offset)
                .retrieve()
                .body(UpdatesResponse.class);
        if (response == null || !response.ok() || response.result() == null) {
            return List.of();
        }
        return response.result();
    }

    private record SendMessageRequest(
            @JsonProperty("chat_id") String chatId,
            String text,
            @JsonProperty("parse_mode") String parseMode,
            @JsonProperty("disable_web_page_preview") boolean disableWebPagePreview
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpdatesResponse(boolean ok, List<Update> result) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Update(@JsonProperty("update_id") long updateId, Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(Chat chat, String text) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Chat(long id) {
    }
}
