package com.dartcommons.infrastructure.dart;

import com.dartcommons.infrastructure.dart.dto.DartFinancialResponse;
import com.dartcommons.infrastructure.dart.dto.DartFinancialResponse.FinancialAccountItem;
import com.dartcommons.shared.util.HostWhitelist;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/*
 * [목적] DART OpenAPI fnlttSinglAcnt.json(단일회사 주요계정)을 호출해 분기 재무 스냅샷을 수집.
 *       연결재무제표(CFS) 우선, 없으면 별도재무제표(OFS) 폴백. 핵심 6계정(매출액·영업이익·순이익·자산·부채·자본)만 추출.
 * [이유] Stage 5 재무 분석 프롬프트 입력 데이터 소스(analysis-stage5-financial-industry Spec R2).
 *       DartClient/DartDocumentClient 패턴 답습: HostWhitelist·RestClient·@Retryable·status 분기.
 *       실측(2026-07-06 삼성전자 2024): status=000 정상, status=013 데이터없음(미제출·신규상장).
 *       금융업은 계정체계 달라 핵심 계정명 매칭 미보장 — Optional.empty 반환(skip).
 * [사이드 임팩트] DART 일 20k 쿼터 소비 — FinancialSyncJob/백필이 throttle 관리(DartApiProperties.contentBackfillThrottleMs 재사용).
 *               상태 013(데이터없음) → Optional.empty 반환(skip 신호). 020/800/900 → RestClientException(재시도 대상).
 * [수정 시 고려사항] reprt_code: 11011=사업보고서/11012=반기/11013=1Q/11014=3Q.
 *                  CFS 없는 종목은 OFS로 폴백 — 연결·별도 혼재 시 분기 불일치 주의.
 *                  thstrm_amount 콤마 포함 문자열 · "-" 미보고 처리는 parseAmount()에서.
 *                  CF(현금흐름) sj_div 추가 필요 시 WANTED_ACCOUNTS Set 확장.
 */
@Component
public class DartFinancialClient {

    private static final Logger log = LoggerFactory.getLogger(DartFinancialClient.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** 핵심 계정명 — 실측 기준(2026-07-06). 금융업 계정체계 상이 시 매칭 실패 → Optional.empty. */
    private static final String ACCOUNT_REVENUE      = "매출액";
    private static final String ACCOUNT_OP_PROFIT    = "영업이익";
    private static final String ACCOUNT_NET_INCOME   = "당기순이익";
    private static final String ACCOUNT_TOTAL_ASSETS = "자산총계";
    private static final String ACCOUNT_TOTAL_LIAB   = "부채총계";
    private static final String ACCOUNT_TOTAL_EQUITY = "자본총계";

    private final RestClient restClient;
    private final DartApiProperties props;

    public DartFinancialClient(DartApiProperties props) {
        HostWhitelist.verify(props.baseUrl(), "DartFinancialClient");
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
                            throw new RestClientException("DART fnlttSinglAcnt HTTP error: " + res.getStatusCode());
                        })
                .build();
    }

    /**
     * 단일 회사 분기 재무 스냅샷 조회.
     * CFS(연결) 우선, 없으면 OFS(별도) 폴백.
     *
     * @param corpCode  DART 고유번호 (8자리)
     * @param bsnsYear  사업연도 (예: "2024")
     * @param reprtCode 보고서 코드 (11011=사업보고서 / 11012=반기 / 11013=1Q / 11014=3Q)
     * @return 핵심 6계정 파싱 결과. 데이터없음(013) 또는 계정 매칭 실패 시 Optional.empty.
     */
    @Retryable(
            retryFor = RestClientException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8_000)
    )
    public Optional<FinancialSnapshot> fetchSnapshot(String corpCode, String bsnsYear, String reprtCode) {
        String body = restClient.get()
                .uri("/fnlttSinglAcnt.json?crtfc_key={key}&corp_code={corp}&bsns_year={year}&reprt_code={code}",
                        props.apiKey(), corpCode, bsnsYear, reprtCode)
                .retrieve()
                .body(String.class);

        DartFinancialResponse resp;
        try {
            resp = MAPPER.readValue(body, DartFinancialResponse.class);
        } catch (Exception e) {
            throw new RestClientException("DART fnlttSinglAcnt JSON 파싱 실패: " + e.getMessage());
        }

        if (resp.isNoData()) {
            log.debug("DartFinancialClient: 데이터없음(013) corpCode={} year={} reprtCode={}", corpCode, bsnsYear, reprtCode);
            return Optional.empty();
        }
        if (!resp.isOk()) {
            // 020/800/900 → transient 재시도 대상(RestClientException), 나머지는 치명적 로그
            String status = resp.status();
            if ("020".equals(status) || "800".equals(status) || "900".equals(status)) {
                throw new RestClientException("DART fnlttSinglAcnt transient error status=" + status);
            }
            log.warn("DartFinancialClient: 비정상 status={} msg={} corpCode={}", status, resp.message(), corpCode);
            return Optional.empty();
        }

        return buildSnapshot(corpCode, bsnsYear, reprtCode, resp.list());
    }

    /** 응답 항목에서 핵심 6계정 추출 — CFS 우선, 없으면 OFS 폴백. */
    private Optional<FinancialSnapshot> buildSnapshot(String corpCode, String bsnsYear, String reprtCode,
                                                       List<FinancialAccountItem> items) {
        // CFS 항목 우선, 없으면 OFS 폴백
        String preferredFs = items.stream().anyMatch(i -> "CFS".equals(i.fsDiv())) ? "CFS" : "OFS";
        List<FinancialAccountItem> filtered = items.stream()
                .filter(i -> preferredFs.equals(i.fsDiv()))
                .toList();

        BigDecimal revenue = findAccount(filtered, "IS", ACCOUNT_REVENUE);
        BigDecimal opProfit = findAccount(filtered, "IS", ACCOUNT_OP_PROFIT);
        BigDecimal netIncome = findAccount(filtered, "IS", ACCOUNT_NET_INCOME);
        BigDecimal totalAssets = findAccount(filtered, "BS", ACCOUNT_TOTAL_ASSETS);
        BigDecimal totalLiab = findAccount(filtered, "BS", ACCOUNT_TOTAL_LIAB);
        BigDecimal totalEquity = findAccount(filtered, "BS", ACCOUNT_TOTAL_EQUITY);

        // 자산총계 없으면 재무 데이터 신뢰 불가(금융업 계정체계 등) → skip
        if (totalAssets == null) {
            log.debug("DartFinancialClient: 자산총계 매칭 실패 — skip corpCode={} year={} reprtCode={} fsDiv={}",
                    corpCode, bsnsYear, reprtCode, preferredFs);
            return Optional.empty();
        }

        return Optional.of(new FinancialSnapshot(
                corpCode, bsnsYear, reprtCode, preferredFs,
                revenue, opProfit, netIncome, totalAssets, totalLiab, totalEquity));
    }

    private static BigDecimal findAccount(List<FinancialAccountItem> items, String sjDiv, String accountNm) {
        return items.stream()
                .filter(i -> sjDiv.equals(i.sjDiv()) && accountNm.equals(i.accountNm()))
                .findFirst()
                .map(i -> parseAmount(i.thstrmAmount()))
                .orElse(null);
    }

    /**
     * DART amount 문자열("227,062,266,000,000", "-", "", null) → BigDecimal.
     * "-" 또는 빈 문자열은 null 반환 — 미보고 계정.
     */
    static BigDecimal parseAmount(String raw) {
        if (raw == null || raw.isBlank() || "-".equals(raw.trim())) return null;
        try {
            return new BigDecimal(raw.replace(",", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * DartFinancialClient 파싱 결과 중간 record — FinancialSnapshot 엔티티 생성 전 전달체.
     * stocks 도메인(FinancialSyncJob)이 직접 소비하므로 infrastructure 패키지에 위치.
     */
    public record FinancialSnapshot(
            String corpCode,
            String bsnsYear,
            String reprtCode,
            String fsDiv,
            BigDecimal revenue,
            BigDecimal opProfit,
            BigDecimal netIncome,
            BigDecimal totalAssets,
            BigDecimal totalLiab,
            BigDecimal totalEquity
    ) {}
}
