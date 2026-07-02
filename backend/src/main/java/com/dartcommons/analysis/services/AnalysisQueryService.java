package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.dto.PriceReactionForecast;
import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.analysis.dto.Stage2Detail;
import com.dartcommons.shared.enums.Tier;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/*
 * [목적] GET /api/v1/disclosures/{id}/analysis — 공시 분석 결과 조회 + 티어별 필드 차등 적용.
 *       Pro+ 티어는 Stage 3 RAG 유사 공시(Stage3RagService.findSimilar)를 실시간으로 조회해 응답에 포함.
 * [이유] 티어 차등(Free/Pro/Premium)은 컨트롤러 레이어 대신 서비스에서 처리해 단일 책임 원칙 유지.
 *       Stage 3 결과를 AnalysisResult 엔티티에 저장하지 않고 조회 시 실시간 쿼리 — Chroma가 SSOT.
 *       분석 결과가 없으면 404 반환 — FE는 "아직 분석 중" UI로 처리.
 *       AnalysisResultCacheService 경유 조회 — Caffeine 캐시(10분/10_000 항목) 활용.
 * [사이드 임팩트] findSimilar()는 Chroma 비활성(MockChromaClient) 시 빈 리스트 반환 — similar=[]이 아닌 null이 됨
 *               (Free 응답과 구분 불가) — 향후 Chroma 활성 여부를 별도 플래그로 FE에 알릴 수 있음.
 *               findSimilar() 내부 예외는 Stage3RagService에서 warn 로그 후 빈 리스트 반환 — 쿼리 실패가 전체 응답을 차단하지 않음.
 * [수정 시 고려사항] Stage 3 결과 캐싱이 필요하면 AnalysisResultCacheService와 동일한 Caffeine 캐시 적용 검토.
 *                  findSimilar가 느릴 경우(Ollama 임베딩 콜드스타트 등) 타임아웃을 ChromaProperties.timeoutMs로 제어.
 */
@Service
@RequiredArgsConstructor
public class AnalysisQueryService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisQueryService.class);
    /** 예측 차트 후행 거래일 수(D+1~D+5) — disclosure-detail-redesign 목업 기준. */
    private static final int FORECAST_DAYS = 5;

    private final AnalysisResultCacheService analysisResultCacheService;
    private final Stage3RagService stage3RagService;
    private final PriceReactionForecastService forecastService;
    private final ObjectMapper objectMapper;

    public AnalysisResponse getByDisclosureId(Long disclosureId, Tier tier) {
        var result = analysisResultCacheService.findByDisclosureId(disclosureId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 분석이 완료되지 않았습니다.");
        }

        boolean proPlus = tier == Tier.PRO || tier == Tier.PREMIUM;
        List<SimilarDisclosureItem> similar = proPlus
                ? stage3RagService.findSimilar(disclosureId)
                : null;

        // similar가 빈 리스트면 null로 변환 — @JsonInclude(NON_NULL)로 JSON 필드 제외
        if (similar != null && similar.isEmpty()) {
            similar = null;
        }

        // Wave C 예측 차트 — 유사 공시들의 실측 D+1~D+5 평균 등락(방식 A). stock_prices 미적재/표본 없으면 null.
        PriceReactionForecast forecast = (similar != null)
                ? forecastService.forecast(similar, FORECAST_DAYS).orElse(null)
                : null;

        return AnalysisResponse.from(result, tier, similar, parseDetail(result.getStageDetails()), forecast);
    }

    /*
     * stage_details(JSONB 문자열)를 Stage2Detail로 역직렬화 — key_points/호재·악재 요인(Wave 2).
     * null/공백(구버전 분석) 또는 파싱 실패 시 null 반환 → 신규 카드 미노출(FE 폴백). 조회를 막지 않음.
     */
    private Stage2Detail parseDetail(String stageDetails) {
        if (stageDetails == null || stageDetails.isBlank()) return null;
        try {
            return objectMapper.readValue(stageDetails, Stage2Detail.class);
        } catch (Exception e) {
            // e.getMessage()는 Jackson이 소스 스니펫(=stage_details 원문)을 포함할 수 있어 로깅 금지(§11.1 유출 방지). 타입만 기록.
            log.warn("stage_details 역직렬화 실패 — 상세 카드 생략 errType={}", e.getClass().getSimpleName());
            return null;
        }
    }
}
