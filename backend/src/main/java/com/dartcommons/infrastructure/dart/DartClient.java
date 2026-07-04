package com.dartcommons.infrastructure.dart;

import com.dartcommons.disclosure.dto.RawDisclosureItem;
import com.dartcommons.infrastructure.dart.dto.DartListResponse;
import com.dartcommons.shared.util.HostWhitelist;
import com.dartcommons.shared.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
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
 *       RestClient(Spring 6.1+)로 동기 호출, @Retryable 비차단 백오프, 페이지네이션을 처리한다.
 * [이유] 외부 API 호출을 infrastructure/dart로 격리해 disclosure 도메인이 DART 세부사항에 의존하지 않도록
 *       함(CLAUDE.md §3-2). 동기 폴링이라 WebFlux 불필요 — RestClient(spring-web 내장) 채택.
 *       Thread.sleep 블로킹 재시도(deferred HIGH) → @Retryable 어노테이션 기반 비차단 백오프로 교체.
 * [사이드 임팩트] DART 응답 status=020/800/900이면 DartApiException(critical=true) throw.
 *               DisclosurePollingJob이 catch해 로깅. status=013은 정상 빈 결과로 처리.
 *               일 20,000 호출 한도 — 1분 폴링(1,440/일) + 페이지네이션 호출은 여유권.
 *               @Retryable은 AOP 프록시 기반 — fetchList()에서 fetchPage()를 호출하면 same-class라
 *               프록시 우회로 재시도 미작동. 그래서 fetchPage()는 별도 @Component(DartPageFetcher)로 분리.
 * [수정 시 고려사항] 멀티 인스턴스 배포 시 ShedLock 등 분산 락 추가 필요(CLAUDE.md §4).
 *                  pblntf_ty 파라미터로 공시유형 필터 추가 가능(1차 필터 최적화).
 *                  corp_code 파라미터로 특정 기업만 폴링하는 커버리지 최적화 후속 가능.
 *                  백필(임의 날짜 범위 + 페이지네이션)은 BackfillService가 fetchList()를 직접 호출.
 */
@Component
public class DartClient {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int PAGE_SIZE = 100;
    /*
     * DART list.json은 page_no가 total_page 를 초과해도 상태 000과 함께 마지막 페이지를 반복 반환하는
     * 케이스가 관측됨(2026-06-04 backfill 청크 12에서 pageNo=10,000+ stuck). total_page를 종료 조건의
     * 1순위로 쓰고, 그래도 미상의 응답 패턴 대비 절대 상한 가드를 둔다.
     * 일 한도/메모리 보호 차원에서 단일 호출은 최대 PAGE_SIZE * MAX_PAGES = 300,000 항목.
     * MAX_PAGES=3000은 분기/연차 보고서 시즌(2~5월) 관측치 totalPage≈1153 기반 3배 여유로 산정.
     */
    private static final int MAX_PAGES = 3_000;

    private final DartPageFetcher pageFetcher;

    public DartClient(DartPageFetcher pageFetcher) {
        this.pageFetcher = pageFetcher;
    }

    /**
     * 날짜 범위 내 전체 공시 목록을 페이지네이션해서 반환한다.
     * 반환 타입은 도메인-친화 {@link RawDisclosureItem} — disclosure 도메인이 infra DTO에 의존하지 않도록 변환.
     * status=013(데이터없음)은 빈 리스트, 그 외 비정상 status는 DartApiException throw.
     */
    public List<RawDisclosureItem> fetchList(LocalDate bgnDe, LocalDate endDe) {
        List<RawDisclosureItem> result = new ArrayList<>();
        int pageNo = 1;

        while (true) {
            // 다른 빈(pageFetcher) 호출 — AOP 프록시 경유, @Retryable 작동
            DartListResponse response = pageFetcher.fetchPage(bgnDe, endDe, pageNo);

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

            // infra DTO → 도메인 DTO 변환은 infrastructure 책임
            for (DartListResponse.Item item : items) {
                result.add(toDomain(item));
            }

            // 종료 조건 1순위: DART가 명시한 total_page에 도달 (응답에 포함됨, 가장 신뢰)
            Integer totalPage = response.totalPage();
            if (totalPage != null && pageNo >= totalPage) break;
            // 종료 조건 2순위: 페이지 크기 미만 (totalPage 누락 응답 대비)
            if (items.size() < PAGE_SIZE) break;
            // 절대 상한 가드: DART의 알 수 없는 응답 패턴으로 무한 루프 방지
            if (pageNo >= MAX_PAGES) {
                log.warn("DART pagination hit MAX_PAGES={}: bgnDe={}, endDe={}, totalPage={} — 종료 강제",
                        MAX_PAGES, bgnDe, endDe, totalPage);
                break;
            }
            pageNo++;
        }

        return result;
    }

    /**
     * DART 응답 항목 → 도메인-친화 RawDisclosureItem 변환.
     * stockCode는 공백/null 정규화 — 비상장 공시는 null로 통일.
     */
    private static RawDisclosureItem toDomain(DartListResponse.Item item) {
        return new RawDisclosureItem(
                item.rceptNo(),
                item.corpCode(),
                item.stockCodeOrNull(),
                item.corpName(),
                item.reportNm(),
                item.rceptDt()
        );
    }

    /*
     * [목적] @Retryable 어노테이션이 AOP 프록시로 적용되도록 별도 @Component로 분리.
     * [이유] same-class self-invocation은 Spring AOP 프록시를 우회 — DartClient.fetchList()가
     *       this.fetchPage()를 직접 호출하면 @Retryable 미작동. 별도 빈으로 분리해 우회.
     * [사이드 임팩트] DartPageFetcher는 DartClient 내부 구현 디테일 — 외부에서 직접 주입 금지.
     * [수정 시 고려사항] maxAttempts/Backoff 변경 시 DartApiProperties.maxRetries는 무시됨(어노테이션 값 우선).
     *                  추후 properties로 외부화하려면 RetryTemplate + RetryListener 패턴으로 교체.
     */
    @Component
    static class DartPageFetcher {

        private static final Logger log = LoggerFactory.getLogger(DartPageFetcher.class);

        private final RestClient restClient;
        private final DartApiProperties props;

        DartPageFetcher(DartApiProperties props) {
            HostWhitelist.verify(props.baseUrl(), "DartClient");
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

        @Retryable(
                retryFor = RestClientException.class,
                maxAttempts = 3,
                backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30_000)
        )
        public DartListResponse fetchPage(LocalDate bgnDe, LocalDate endDe, int pageNo) {
            log.debug("DART fetchPage attempt: bgnDe={}, endDe={}, pageNo={}", bgnDe, endDe, pageNo);
            try {
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
            } catch (RestClientException e) {
                // 예외 메시지에 URL 쿼리스트링(crtfc_key=...) 포함 가능 — 마스킹 후 재throw
                throw new RestClientException(SecretMasker.mask(e.getMessage()));
            }
        }
    }
}
