package com.dartcommons.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

/*
 * [목적] 공시 후 D+1~D+5 예측 등락 — 과거 유사 공시들의 실측 반응 평균(방식 A). disclosure-detail-redesign 예측 차트(#8/#9).
 * [이유] LLM 미래 예측(방식 B) 비채택(자본시장법 §11.1). "과거 유사 사례 평균 등락"만 제공 — 실측 근거.
 *       series는 일자별 평균, sampleSize는 반응 계산에 성공한 유사 공시 수(신뢰도 표시용).
 * [사이드 임팩트] AnalysisResponse.priceReactionForecast로 Pro+ 티어에만 노출(similar_disclosures와 동일 게이트).
 *               stock_prices 미적재 시 sampleSize=0 → 상위에서 null 반환 → FE 차트 미노출.
 * [수정 시 고려사항] avg5dPct는 마지막 일자(D+5 또는 그 이하) 평균. days 확장 시 series 길이만 변동.
 *                  카피는 "예측" 단정 금지 — "과거 유사 사례 평균"으로 표기(자본시장법).
 */
public record PriceReactionForecast(
        @JsonProperty("series") List<DayReaction> series,
        @JsonProperty("sample_size") int sampleSize,
        @JsonProperty("avg_5d_pct") BigDecimal avg5dPct
) {
    /** D+day 평균 등락률(%). */
    public record DayReaction(
            int day,
            @JsonProperty("avg_pct") BigDecimal avgPct
    ) {}
}
