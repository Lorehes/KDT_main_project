package com.dartcommons.infrastructure.krx;

import com.dartcommons.shared.util.HostWhitelist;
import com.dartcommons.shared.util.SecretMasker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/*
 * [목적] KRX 정보데이터시스템(data.krx.co.kr)에서 전종목 시장/섹터·일별 종가를 호출.
 *       StockMasterSyncJob(분기 1회, 시장/섹터)과 KrxPriceSyncJob(일 1회, 종가) 두 잡이 소비.
 * [이유] DART corpCode는 mapping만, market/sector/종가는 KRX에서 보강.
 *       종가 BLD: MDCSTAT01501(전종목시세), TDD_CLSPRC 필드(pykrx/FDR 소스 실측 확인).
 *       네트워크 환경에 따라 KRX 직접 접근이 차단될 수 있어 GitHub cache CSV를 폴백으로 사용.
 * [사이드 임팩트] KRX API는 비공식·무공지 변경 리스크(응답 필드명 변동 시 파싱 실패).
 *               파싱 실패 시 빈 Map 반환 + ERROR 로그 → SyncJob은 우아하게 무시.
 *               @Retryable(fetchAllBasicInfo)는 네트워크 오류만 재시도 — LOGOUT 응답은 즉시 폴백.
 *               GitHub cache 폴백 URL은 외부 레포 의존 — 서비스 중단·포맷 변경 시 종가 미수집(다음 배치 재시도).
 *               이상치 필터 2단: 1단(isValidPrice — 1원 미만 절대 차단) + 2단(KrxPriceSyncJob — 전일 대비 ±50%).
 *               externalRestClient는 HostWhitelist 밖 — URL을 컴파일 상수로만 제한하여 SSRF 방어.
 * [수정 시 고려사항] MDCSTAT01901 필드(MKT_NM·IDX_IND_NM), MDCSTAT01501 필드(ISU_SRT_CD·TDD_CLSPRC)는
 *                  KRX 페이지 변경 시 해당 record만 갱신.
 *                  Stage 5 착수 시 fetchAllClosePrices()를 stock_prices 시계열 테이블 기반으로 교체 가능
 *                  (StockPriceProvider seam이 격리하므로 이 클래스 변경 최소화).
 *                  isValidPrice() 임계(1원)·ANOMALY_THRESHOLD(±50%) 조정은 각 위치의 상수만 변경.
 */
@Component
public class KrxClient {

    private static final Logger log = LoggerFactory.getLogger(KrxClient.class);
    private static final DateTimeFormatter YYYYMMDD    = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter YYYY_MM_DD  = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** 전종목 기본정보(시장/섹터) BLD. */
    private static final String BLD_STOCK_INFO  = "dbms/MDC/STAT/standard/MDCSTAT01901";
    /** 전종목 시세(종가) BLD — pykrx·FinanceDataReader 소스 실측 확인(TDD_CLSPRC 필드). */
    private static final String BLD_STOCK_PRICE = "dbms/MDC/STAT/standard/MDCSTAT01501";
    /** 최근 거래일 조회 — B128.bld resource bundle. HTTP(not HTTPS) 응답 정상 확인. */
    private static final String B128_URL = "http://data.krx.co.kr/comm/bldAttendant/executeForResourceBundle.cmd?baseName=krx.mdc.i18n.component&key=B128.bld";
    /** GitHub cache CSV 폴백 — FinanceDataReader 프로젝트 유지. 포맷: Code,Close 컬럼 포함. */
    private static final String GITHUB_CACHE_URL = "https://raw.githubusercontent.com/FinanceData/fdr_krx_data_cache/refs/heads/master/data/listing/krx/%s.csv";

    private final RestClient restClient;
    /**
     * B128.bld(HTTP) · GitHub cache CSV 폴백용 — baseUrl 없이 절대 URI 사용. 10s connect / 30s read 타임아웃.
     * HostWhitelist 검증 밖에 있음 — SSRF 방어는 "호출 URL을 컴파일 상수(B128_URL·GITHUB_CACHE_URL)로만 제한"으로 대체.
     * 이 클라이언트로 사용자 입력값·환경변수 URL을 호출하는 것은 절대 금지.
     */
    private final RestClient externalRestClient;
    private final KrxApiProperties props;
    private final ObjectMapper objectMapper;

    public KrxClient(KrxApiProperties props) {
        HostWhitelist.verify(props.baseUrl(), "KrxClient");
        this.props = props;

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMs()))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs() * 3L));  // 전종목 호출 여유
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();

        // B128(HTTP) + GitHub cache는 별도 타임아웃 클라이언트 사용 — RestClient.create()는 타임아웃 미설정 위험
        HttpClient externalHttpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var externalFactory = new org.springframework.http.client.JdkClientHttpRequestFactory(externalHttpClient);
        externalFactory.setReadTimeout(Duration.ofSeconds(30));
        this.externalRestClient = RestClient.builder()
                .requestFactory(externalFactory)
                .build();

        this.objectMapper = new ObjectMapper();
    }

    /**
     * 전종목 시장/섹터 정보 조회.
     * <p>
     * 예상 응답 (검증 필요): {@code {"OutBlock_1": [{"ISU_SRT_CD":"005930","MKT_NM":"KOSPI","IDX_IND_NM":"전기·전자",...}, ...]}}
     *
     * @return stockCode → StockBasicInfo 매핑. 호출 실패 시 빈 Map.
     */
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30_000)
    )
    public Map<String, StockBasicInfo> fetchAllBasicInfo() {
        log.info("KRX fetchAllBasicInfo start — bld={}", BLD_STOCK_INFO);
        try {
            String body = restClient.post()
                    .uri("/comm/bldAttendant/getJsonData.cmd")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(buildForm())
                    .retrieve()
                    .body(String.class);

            if (body == null || body.isBlank()) {
                log.warn("KRX 응답 비어있음");
                return Map.of();
            }
            return parseResponse(body);
        } catch (Exception e) {
            log.error("KRX fetchAllBasicInfo 실패 — corp_code만 갱신됩니다: {}", SecretMasker.mask(e.getMessage()));
            return Map.of();
        }
    }

    private MultiValueMap<String, String> buildForm() {
        // KRX bldAttendant 표준 폼 파라미터 (pykrx 라이브러리 패턴 기준)
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("bld", BLD_STOCK_INFO);
        form.add("mktId", "ALL");  // KOSPI+KOSDAQ+KONEX
        form.add("trdDd", LocalDate.now().format(YYYYMMDD));
        form.add("share", "1");
        form.add("money", "1");
        form.add("csvxls_isNo", "false");
        return form;
    }

    private Map<String, StockBasicInfo> parseResponse(String body) throws Exception {
        JsonNode root = objectMapper.readTree(body);
        JsonNode outBlock = root.path("OutBlock_1");
        if (!outBlock.isArray()) {
            log.warn("KRX 응답 OutBlock_1 배열 아님 — 응답 포맷 변경 가능성. 앞 200자: {}",
                    body.length() > 200 ? body.substring(0, 200) : body);
            return Map.of();
        }

        Map<String, StockBasicInfo> result = new HashMap<>(outBlock.size());
        for (JsonNode item : outBlock) {
            String stockCode = item.path("ISU_SRT_CD").asText("").trim();
            if (stockCode.length() != 6) continue;
            String market = item.path("MKT_NM").asText("").trim();
            String sector = item.path("IDX_IND_NM").asText("").trim();
            result.put(stockCode, new StockBasicInfo(
                    market.isEmpty() ? null : market,
                    sector.isEmpty() ? null : sector
            ));
        }
        log.info("KRX 종목 정보 {} 건 파싱", result.size());
        return result;
    }

    /**
     * 전종목 일별 종가 조회 — KrxPriceSyncJob(일배치)이 호출.
     * <p>
     * 전략: ① B128.bld로 최근 거래일 취득 → ② KRX MDCSTAT01501 → ③ GitHub cache CSV 폴백.
     * LOGOUT/오류 시 각 단계를 WARN 로그 후 다음 단계로 넘어감.
     *
     * @return stockCode → StockCloseInfo 매핑. 전 단계 실패 시 빈 Map.
     */
    public Map<String, StockCloseInfo> fetchAllClosePrices() {
        String tradeDate = fetchLatestTradingDate();
        if (tradeDate == null) {
            // KST 기준 오늘 날짜 폴백 — JVM 기본 타임존(UTC 가능)으로 전일 날짜 참조 방지
            tradeDate = LocalDate.now(ZoneId.of("Asia/Seoul")).format(YYYYMMDD);
        }

        // 1차: KRX 직접
        try {
            Map<String, StockCloseInfo> result = fetchClosePricesFromKrx(tradeDate);
            if (!result.isEmpty()) {
                log.info("KRX 종가 {} 건 수집 (거래일 {})", result.size(), tradeDate);
                return result;
            }
            log.warn("KRX 종가 응답 비어있음(LOGOUT 가능) — GitHub cache 폴백 시도");
        } catch (Exception e) {
            log.warn("KRX 종가 fetch 실패 — GitHub cache 폴백: {}", SecretMasker.mask(e.getMessage()));
        }

        // 2차: GitHub cache CSV 폴백
        try {
            LocalDate date = LocalDate.parse(tradeDate, YYYYMMDD);
            Map<String, StockCloseInfo> result = fetchClosePricesFromGithubCache(date);
            log.info("GitHub cache 종가 {} 건 수집 (거래일 {})", result.size(), date);
            return result;
        } catch (Exception e) {
            log.error("GitHub cache 종가 폴백도 실패 — 이번 배치 종가 미수집: {}", SecretMasker.mask(e.getMessage()));
            return Map.of();
        }
    }

    /** B128.bld resource bundle에서 최근 거래일(YYYYMMDD) 조회. 실패 시 null 반환. */
    private String fetchLatestTradingDate() {
        try {
            String body = externalRestClient.get()
                    .uri(B128_URL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Referer", "https://data.krx.co.kr/contents/MDC/MDI/outerLoader/index.cmd")
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) return null;
            JsonNode root = objectMapper.readTree(body);
            // output 배열이 비어있으면 null 반환 — get(0) NPE 방지
            JsonNode outputNode = root.path("result").path("output");
            if (!outputNode.isArray() || outputNode.isEmpty()) return null;
            String dt = outputNode.get(0).path("max_work_dt").asText(null);
            return (dt == null || dt.isBlank()) ? null : dt;
        } catch (Exception e) {
            log.warn("B128.bld 최근 거래일 조회 실패: {}", e.getMessage());
            return null;
        }
    }

    /** KRX MDCSTAT01501으로 전종목 종가 조회. LOGOUT 또는 빈 응답이면 빈 Map. */
    private Map<String, StockCloseInfo> fetchClosePricesFromKrx(String tradeDate) throws Exception {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("bld", BLD_STOCK_PRICE);
        form.add("mktId", "ALL");
        form.add("trdDd", tradeDate);
        form.add("share", "1");
        form.add("money", "1");
        form.add("csvxls_isNo", "false");

        String body = restClient.post()
                .uri("/comm/bldAttendant/getJsonData.cmd")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .header("Referer", "https://data.krx.co.kr/contents/MDC/MDI/outerLoader/index.cmd")
                .body(form)
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank() || "LOGOUT".equals(body.strip())) return Map.of();

        JsonNode root = objectMapper.readTree(body);
        JsonNode outBlock = root.path("OutBlock_1");
        if (!outBlock.isArray() || outBlock.isEmpty()) return Map.of();

        LocalDate asof = LocalDate.parse(tradeDate, YYYYMMDD);
        Map<String, StockCloseInfo> result = new HashMap<>(outBlock.size());
        int skipped = 0;
        for (JsonNode item : outBlock) {
            String stockCode = item.path("ISU_SRT_CD").asText("").trim();
            if (stockCode.length() != 6) continue;
            String rawPrice = item.path("TDD_CLSPRC").asText("").replace(",", "").trim();
            if (rawPrice.isEmpty()) continue;
            try {
                BigDecimal price = new BigDecimal(rawPrice);
                if (!isValidPrice(price)) {
                    log.warn("KRX 비정상 가격 스킵 — stockCode={}, price={}", stockCode, rawPrice);
                    skipped++;
                    continue;
                }
                result.put(stockCode, new StockCloseInfo(price, asof));
            } catch (NumberFormatException ignored) {
                log.warn("KRX 가격 파싱 오류 스킵 — stockCode={}, raw='{}'", stockCode, rawPrice);
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("KRX 비정상 가격 스킵 총 {}건 (1원 미만·포맷 오류)", skipped);
        }
        return result;
    }

    /** GitHub cache CSV 폴백 — 포맷: header행(Code,Close,...) + 데이터행. */
    private Map<String, StockCloseInfo> fetchClosePricesFromGithubCache(LocalDate date) throws Exception {
        String url = String.format(GITHUB_CACHE_URL, date.format(YYYY_MM_DD));
        String csv = externalRestClient.get()
                .uri(url)
                .header("User-Agent", "Mozilla/5.0")
                .retrieve()
                .body(String.class);
        if (csv == null || csv.isBlank()) return Map.of();

        String[] lines = csv.split("\n");
        if (lines.length < 2) return Map.of();

        // 헤더 파싱으로 컬럼 인덱스 탐색
        String[] headers = lines[0].trim().split(",");
        int codeIdx = -1, closeIdx = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i].trim().replace("﻿", ""); // BOM 제거
            if ("Code".equals(h)) codeIdx = i;
            else if ("Close".equals(h)) closeIdx = i;
        }
        if (codeIdx < 0 || closeIdx < 0) {
            log.warn("GitHub cache CSV 헤더 파싱 실패 — Code/Close 컬럼 미확인. 헤더: {}", lines[0]);
            return Map.of();
        }

        Map<String, StockCloseInfo> result = new HashMap<>(lines.length - 1);
        int skipped = 0;
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",", -1);
            if (cols.length <= Math.max(codeIdx, closeIdx)) continue;
            String stockCode = cols[codeIdx].trim();
            String rawPrice  = cols[closeIdx].trim();
            if (stockCode.length() != 6 || rawPrice.isEmpty()) continue;
            try {
                BigDecimal price = new BigDecimal(rawPrice);
                if (!isValidPrice(price)) {
                    log.warn("GitHub cache 비정상 가격 스킵 — stockCode={}, price={}", stockCode, rawPrice);
                    skipped++;
                    continue;
                }
                result.put(stockCode, new StockCloseInfo(price, date));
            } catch (NumberFormatException ignored) {
                log.warn("GitHub cache 가격 파싱 오류 스킵 — stockCode={}, raw='{}'", stockCode, rawPrice);
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("GitHub cache 비정상 가격 스킵 총 {}건 (1원 미만·포맷 오류)", skipped);
        }
        return result;
    }

    /** 가격 이상치 1단 방어 — 1원 미만은 명백한 데이터 오류(상장 종목 최저가 1원). package-private: KrxClientTest 직접 호출(Option C). */
    boolean isValidPrice(BigDecimal price) {
        return price != null && price.compareTo(BigDecimal.ONE) >= 0;
    }

    /**
     * 종목 기본정보 — market(KOSPI/KOSDAQ/KONEX) + sector(업종명).
     */
    public record StockBasicInfo(String market, String sector) {
    }

    /**
     * 종목 종가 정보 — KrxPriceSyncJob · StockPriceProvider가 소비.
     */
    public record StockCloseInfo(BigDecimal closePrice, LocalDate priceAsof) {
    }

}
