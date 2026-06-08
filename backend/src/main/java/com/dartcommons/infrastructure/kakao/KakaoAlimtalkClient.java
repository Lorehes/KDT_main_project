package com.dartcommons.infrastructure.kakao;

import com.dartcommons.shared.util.HostWhitelist;
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

/*
 * [목적] 카카오 비즈메시지 알림톡 REST API를 호출해 단건 메시지를 발송하는 인프라 클라이언트.
 *       RestClient(Spring 6.1+) + @Retryable 백오프 패턴(DartClient와 동일).
 * [이유] infrastructure/kakao로 격리해 notification 도메인이 카카오 API 세부사항에 의존하지 않도록 함
 *       (CLAUDE.md §3-2, feature_structure §6).
 * [사이드 임팩트] HostWhitelist에 alimtalk-api.kakao.com 추가 필수 — 누락 시 부팅 실패.
 *               카카오 API key/senderKey/templateCode 미설정 시 @Validated로 구동 실패.
 *               알림톡 template 미승인 시 카카오 서버가 실패 응답 → FAILED 기록 후 재시도.
 * [수정 시 고려사항] 실제 endpoint·request body 형식은 카카오 비즈메시지 가이드 확인 필요.
 *                  MVP는 단건 발송(send)만. 다건 배치는 /v1/message/send (multi) 후속.
 *                  templateCode는 properties로 관리. 내용 변경 시 카카오 템플릿 심사 다시 필요.
 */
@Component
public class KakaoAlimtalkClient {

    private static final Logger log = LoggerFactory.getLogger(KakaoAlimtalkClient.class);
    // 확인 필요: 실제 endpoint는 카카오 비즈메시지 가이드 참조
    private static final String SEND_PATH = "/v1/message/send";

    private final RestClient restClient;
    private final KakaoAlimtalkProperties props;

    public KakaoAlimtalkClient(KakaoAlimtalkProperties props) {
        HostWhitelist.verify(props.baseUrl(), "KakaoAlimtalkClient");
        this.props = props;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMs()))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultHeader("Authorization", "KakaoAK " + props.apiKey())
                .defaultStatusHandler(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new RestClientException("Kakao Alimtalk HTTP error: " + res.getStatusCode());
                        })
                .build();
    }

    /**
     * 알림톡 단건 발송. 성공 시 true, 실패(재시도 소진) 시 RestClientException throw.
     * 수신자 전화번호는 하이픈 없는 11자리 숫자 문자열(예: "01012345678").
     */
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 10_000)
    )
    public boolean send(String phoneNumber, String message) {
        if (phoneNumber == null || phoneNumber.isBlank()) {
            throw new IllegalArgumentException("phoneNumber must not be blank");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
        log.debug("Kakao Alimtalk send attempt: phone=[REDACTED]");
        var request = new AlimtalkRequest(props.senderKey(), props.templateCode(), phoneNumber, message);
        restClient.post()
                .uri(SEND_PATH)
                .body(request)
                .retrieve()
                .toBodilessEntity();
        return true;
    }

    /*
     * 확인 필요: 실제 request body 필드명은 카카오 비즈메시지 REST API 가이드 참조.
     * 현재는 일반적으로 알려진 JSON 형식 사용.
     */
    private record AlimtalkRequest(
            @JsonProperty("sender_key") String senderKey,
            @JsonProperty("template_code") String templateCode,
            @JsonProperty("receiver_1") String receiver1,
            @JsonProperty("msg_1") String msg1
    ) {
    }
}
