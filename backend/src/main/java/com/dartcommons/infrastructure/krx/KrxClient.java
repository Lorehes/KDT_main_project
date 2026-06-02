package com.dartcommons.infrastructure.krx;

import com.fasterxml.jackson.annotation.JsonProperty;
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

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * [목적] KRX 정보데이터시스템(data.krx.co.kr)에서 전종목 시장/섹터 정보를 호출.
 *       StockMasterSyncJob이 분기 1회 호출 — 시장/업종 재분류 반영.
 * [이유] DART corpCode는 corp_code↔stock_code 매핑만, market/sector는 KRX에서 보강.
 *       알려진 KRX 공개 엔드포인트(bldAttendant/getJsonData)는 pykrx 라이브러리가 사용 중이며
 *       OTP/인증 불필요. POST form-urlencoded + JSON 응답 패턴.
 * [사이드 임팩트] **검증 미완료** — Spec 카드 #1 실측 권장(사용자 환경에서 curl 또는 통합 테스트로 검증).
 *               KRX API는 사전 공지 없이 변경될 수 있음(비공식 인터페이스) — 응답 필드명 변동 시 파싱 실패.
 *               파싱 실패 시 빈 Map 반환 + ERROR 로그 — SyncJob은 우아하게 무시(corp_code만 갱신).
 *               @Retryable로 일시적 네트워크 오류는 비차단 백오프 처리.
 * [수정 시 고려사항] KRX 응답 필드명(ISU_SRT_CD, MKT_NM, IDX_IND_NM 등)은 공개 페이지의 데이터 셀 키.
 *                  변경 발견 시 KrxStockInfoResponse record 필드명만 갱신.
 *                  bld 파라미터값(MDCSTAT01901)이 변경되면 properties로 외부화 검토.
 *                  대용량 전종목(~2,800종목) 1회 호출 — JSON ~수MB. 메모리 부담 무시 가능.
 */
@Component
public class KrxClient {

    private static final Logger log = LoggerFactory.getLogger(KrxClient.class);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** KRX 정보데이터시스템 — 전종목 시세/기본정보 데이터 ID. */
    private static final String BLD_STOCK_INFO = "dbms/MDC/STAT/standard/MDCSTAT01901";

    private final RestClient restClient;
    private final KrxApiProperties props;
    private final ObjectMapper objectMapper;

    public KrxClient(KrxApiProperties props) {
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
            log.error("KRX fetchAllBasicInfo 실패 — corp_code만 갱신됩니다: {}", e.getMessage());
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
     * 종목 기본정보 — market(KOSPI/KOSDAQ/KONEX) + sector(업종명).
     */
    public record StockBasicInfo(String market, String sector) {
    }

    /**
     * 종목 마스터 일괄 조회 결과 — SyncJob 편의용.
     */
    public List<Map.Entry<String, StockBasicInfo>> fetchAllAsList() {
        return fetchAllBasicInfo().entrySet().stream().toList();
    }

    /** Jackson 응답 매핑용 — KRX OutBlock_1 항목 (필드 변경 시 갱신). */
    public record KrxStockInfoItem(
            @JsonProperty("ISU_SRT_CD") String stockCode,
            @JsonProperty("MKT_NM") String marketName,
            @JsonProperty("IDX_IND_NM") String sectorName
    ) {
    }
}
