package com.dartcommons.infrastructure.dart;

import com.dartcommons.infrastructure.dart.dto.DartListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/*
 * [목적] DART OpenAPI list.json을 호출해 신규 공시 목록을 수집하는 인프라 클라이언트.
 *       RestClient(Spring 6.1+)로 동기 호출, 지수 백오프 재시도, 페이지네이션을 처리한다.
 * [이유] 외부 API 호출을 infrastructure/dart로 격리해 disclosure 도메인이 DART 세부사항에 의존하지 않도록
 *       함(CLAUDE.md §3-2). 동기 폴링이라 WebFlux 불필요 — RestClient(spring-web 내장) 채택.
 * [사이드 임팩트] DART 응답 status=020/800/900이면 DartApiException(critical=true) throw.
 *               DisclosurePollingJob이 catch해 로깅. status=013은 정상 빈 결과로 처리.
 *               일 20,000 호출 한도 — 1분 폴링(1,440/일) + 페이지네이션 호출은 여유권.
 * [수정 시 고려사항] 멀티 인스턴스 배포 시 ShedLock 등 분산 락 추가 필요(CLAUDE.md §4).
 *                  pblntf_ty 파라미터로 공시유형 필터 추가 가능(1차 필터 최적화).
 *                  corp_code 파라미터로 특정 기업만 폴링하는 커버리지 최적화 후속 가능.
 */
@Component
public class DartClient {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAGE_SIZE = 100;

    private final RestClient restClient;
    private final DartApiProperties props;

    public DartClient(DartApiProperties props) {
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
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new RestClientException("DART HTTP error: " + res.getStatusCode());
                        })
                .build();
    }

    /**
     * 날짜 범위 내 전체 공시 목록을 페이지네이션해서 반환한다.
     * status=013(데이터없음)은 빈 리스트, 그 외 비정상 status는 DartApiException throw.
     */
    public List<DartListResponse.Item> fetchList(LocalDate bgnDe, LocalDate endDe) {
        List<DartListResponse.Item> result = new ArrayList<>();
        int pageNo = 1;

        while (true) {
            DartListResponse response = fetchPageWithRetry(bgnDe, endDe, pageNo);

            if (response.isNoData()) {
                log.debug("DART list.json status=013 (no data): bgnDe={}, endDe={}", bgnDe, endDe);
                break;
            }
            if (!response.isOk()) {
                DartApiException ex = new DartApiException(response.status(), response.message());
                if (ex.isCritical()) {
                    log.error("DART API critical error — check API key or system status: {}", ex.getMessage());
                } else {
                    log.warn("DART API error: {}", ex.getMessage());
                }
                throw ex;
            }

            List<DartListResponse.Item> items = response.safeList();
            if (items.isEmpty()) break;
            result.addAll(items);

            // 반환된 항목이 PAGE_SIZE 미만이면 마지막 페이지 — totalCount 신뢰보다 안전
            if (items.size() < PAGE_SIZE) break;
            pageNo++;
        }

        return result;
    }

    private DartListResponse fetchPageWithRetry(LocalDate bgnDe, LocalDate endDe, int pageNo) {
        int attempt = 0;
        while (true) {
            try {
                return fetchPage(bgnDe, endDe, pageNo);
            } catch (Exception e) {
                attempt++;
                if (attempt > props.maxRetries()) {
                    log.error("DART list.json failed after {} retries: {}", props.maxRetries(), e.getMessage());
                    throw e;
                }
                long delayMs = Math.min((long) Math.pow(2, attempt) * 1000L, 30_000L);
                log.warn("DART list.json attempt {}/{} failed, retrying in {}ms: {}",
                        attempt, props.maxRetries(), delayMs, e.getMessage());
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("Interrupted during DART retry backoff", ie);
                }
            }
        }
    }

    private DartListResponse fetchPage(LocalDate bgnDe, LocalDate endDe, int pageNo) {
        return restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/list.json")
                        .queryParam("crtfc_key", props.apiKey())
                        .queryParam("bgn_de", bgnDe.format(YYYYMMDD))
                        .queryParam("end_de", endDe.format(YYYYMMDD))
                        .queryParam("page_no", pageNo)
                        .queryParam("page_count", PAGE_SIZE)
                        .build())
                .retrieve()
                .body(DartListResponse.class);
    }
}
