package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.PriceReactionForecast;
import com.dartcommons.analysis.dto.PriceReactionForecast.DayReaction;
import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.stocks.services.StockPriceProvider;
import com.dartcommons.stocks.services.StockPriceProvider.PriceReaction;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/*
 * [목적] 유사 공시 집합의 실측 D+1~D+days 등락을 일자별 평균해 예측 차트 데이터 생성(krx-price-timeseries Wave C).
 *       방식 A(과거 실측 평균) — LLM 미래 예측(방식 B) 비채택(자본시장법 §11.1).
 * [이유] 각 유사 공시의 (stock_code, 접수일 D0)로 stock_prices 반응을 조회 → 일자별 평균.
 *       stock_code는 disclosure_id → Disclosure.stockCode로 해석(Chroma 메타에 stock_code 미포함, disclosure_id는 존재).
 *       analysis→disclosure(read-only, EmbeddingBackfillService 선례) + analysis→stocks(master 예외, CLAUDE.md §3-2) 의존.
 * [사이드 임팩트] AnalysisQueryService가 Pro+에서 호출. stock_prices 미적재(Wave A/B 전)면 sampleSize=0 → Optional.empty.
 *               반응 계산 실패 유사 공시는 표본에서 제외(추측 금지) — 짧은 series 가능.
 * [수정 시 고려사항] days 기본 5(D+1~D+5). 확장 시 forecast(similar, days) 인자만 변경.
 *                  disclosure_id null(메타 파싱 실패) 또는 rcept_dt 파싱 실패 유사 공시는 스킵.
 *                  수정주가 미보정(raw) — 분할·합병 구간 왜곡 가능(krx-price-timeseries Spec 확정).
 */
@Service
@RequiredArgsConstructor
public class PriceReactionForecastService {

    private static final Logger log = LoggerFactory.getLogger(PriceReactionForecastService.class);

    private final DisclosureRepository disclosureRepository;
    private final StockPriceProvider stockPriceProvider;

    /**
     * 유사 공시들의 D+1~D+days 실측 반응을 일자별 평균.
     *
     * @param similar Stage 3 유사 공시 목록(Pro+). null·빈 리스트면 Optional.empty.
     * @param days    후행 거래일 수(예측 봉 개수)
     * @return 표본이 1건 이상이면 forecast, 없으면 Optional.empty(FE 차트 미노출).
     */
    public Optional<PriceReactionForecast> forecast(List<SimilarDisclosureItem> similar, int days) {
        if (similar == null || similar.isEmpty() || days <= 0) return Optional.empty();

        // disclosure_id → stockCode 일괄 조회(N+1 방지) — 유사 공시 ≤10건이라 findAllById 1회.
        List<Long> ids = similar.stream().map(SimilarDisclosureItem::disclosureId).filter(java.util.Objects::nonNull).toList();
        Map<Long, String> stockByDisclosureId = new java.util.HashMap<>();
        for (Disclosure d : disclosureRepository.findAllById(ids)) {
            if (d.getStockCode() != null) stockByDisclosureId.put(d.getId(), d.getStockCode());
        }

        // day(1..days) → 해당 일자 등락률 표본 모음
        Map<Integer, List<BigDecimal>> byDay = new TreeMap<>();
        int sampleSize = 0;

        for (SimilarDisclosureItem item : similar) {
            if (item.disclosureId() == null) continue;
            String stockCode = stockByDisclosureId.get(item.disclosureId());
            if (stockCode == null) continue;

            LocalDate d0;
            try {
                d0 = LocalDate.parse(item.rceptDt());  // ISO "YYYY-MM-DD" (Stage3RagService buildMetadata)
            } catch (Exception e) {
                log.debug("forecast: rcept_dt 파싱 실패 — rceptNo={} rceptDt={}", item.rceptNo(), item.rceptDt());
                continue;
            }

            List<PriceReaction> reactions = stockPriceProvider.findReactionSeries(stockCode, d0, days);
            if (reactions.isEmpty()) continue;

            sampleSize++;
            for (PriceReaction r : reactions) {
                byDay.computeIfAbsent(r.day(), k -> new ArrayList<>()).add(r.pct());
            }
        }

        if (sampleSize == 0) return Optional.empty();

        List<DayReaction> series = new ArrayList<>(byDay.size());
        for (Map.Entry<Integer, List<BigDecimal>> e : byDay.entrySet()) {
            series.add(new DayReaction(e.getKey(), average(e.getValue())));
        }
        // avg_5d_pct = 마지막(최대 D+day) 일자 평균 — mockup "5일 평균" 표기용
        BigDecimal avg5d = series.isEmpty() ? null : series.get(series.size() - 1).avgPct();

        return Optional.of(new PriceReactionForecast(series, sampleSize, avg5d));
    }

    private static BigDecimal average(List<BigDecimal> values) {
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
    }
}
