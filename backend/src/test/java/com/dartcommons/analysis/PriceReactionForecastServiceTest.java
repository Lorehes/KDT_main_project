package com.dartcommons.analysis;

import com.dartcommons.analysis.dto.PriceReactionForecast;
import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.analysis.services.PriceReactionForecastService;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.stocks.services.StockPriceProvider;
import com.dartcommons.stocks.services.StockPriceProvider.PriceReaction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/*
 * [목적] PriceReactionForecastService 순수 로직 검증 — 유사 공시 일자별 평균, 표본 스킵(disclosure_id null·반응 없음).
 * [이유] 평균/스킵 산술은 DB 없이 Mockito로 결정론적 검증(빠름). findReactionSeries의 실 SQL/math는 별도 Testcontainers IT.
 * [사이드 임팩트] 없음 — Mock 기반 단위 테스트.
 */
class PriceReactionForecastServiceTest {

    private final DisclosureRepository disclosureRepository = mock(DisclosureRepository.class);
    private final StockPriceProvider stockPriceProvider = mock(StockPriceProvider.class);
    private final PriceReactionForecastService service =
            new PriceReactionForecastService(disclosureRepository, stockPriceProvider);

    private SimilarDisclosureItem item(Long disclosureId, String rceptDt) {
        return new SimilarDisclosureItem(disclosureId, "R" + disclosureId, "테스트기업", "00000000", "OTHER", rceptDt, 0.9);
    }

    private Disclosure disclosure(Long id, String stockCode) {
        Disclosure d = mock(Disclosure.class);
        when(d.getId()).thenReturn(id);
        when(d.getStockCode()).thenReturn(stockCode);
        return d;
    }

    @Test
    @DisplayName("유사 공시 2건 일자별 평균 — D+1 (+2%, -4%) → -1.00%, sampleSize=2")
    void forecast_averagesAcrossSamples() {
        Disclosure d1 = disclosure(1L, "005930");
        Disclosure d2 = disclosure(2L, "000660");
        when(disclosureRepository.findAllById(any())).thenReturn(List.of(d1, d2));
        when(stockPriceProvider.findReactionSeries(eq("005930"), any(), eq(2))).thenReturn(List.of(
                new PriceReaction(1, new BigDecimal("2.00")), new PriceReaction(2, new BigDecimal("3.00"))));
        when(stockPriceProvider.findReactionSeries(eq("000660"), any(), eq(2))).thenReturn(List.of(
                new PriceReaction(1, new BigDecimal("-4.00")), new PriceReaction(2, new BigDecimal("-1.00"))));

        Optional<PriceReactionForecast> out = service.forecast(
                List.of(item(1L, "2026-06-01"), item(2L, "2026-06-02")), 2);

        assertThat(out).isPresent();
        PriceReactionForecast f = out.get();
        assertThat(f.sampleSize()).isEqualTo(2);
        assertThat(f.series()).hasSize(2);
        assertThat(f.series().get(0).avgPct()).isEqualByComparingTo("-1.00");  // (2 + -4)/2
        assertThat(f.series().get(1).avgPct()).isEqualByComparingTo("1.00");   // (3 + -1)/2
        assertThat(f.avg5dPct()).isEqualByComparingTo("1.00");                 // 마지막 일자 평균
    }

    @Test
    @DisplayName("disclosure_id null·반응 없음 유사 공시는 표본에서 제외")
    void forecast_skipsUnresolvableSamples() {
        Disclosure d1 = disclosure(1L, "005930");
        Disclosure d3 = disclosure(3L, "068270");
        when(disclosureRepository.findAllById(any())).thenReturn(List.of(d1, d3));
        when(stockPriceProvider.findReactionSeries(eq("005930"), any(), anyInt())).thenReturn(List.of(
                new PriceReaction(1, new BigDecimal("1.50"))));
        // id=3: stock_prices 반응 없음(빈 리스트) → 스킵
        when(stockPriceProvider.findReactionSeries(eq("068270"), any(), anyInt())).thenReturn(List.of());

        Optional<PriceReactionForecast> out = service.forecast(List.of(
                item(1L, "2026-06-01"),
                item(null, "2026-06-01"),   // disclosure_id null → 스킵
                item(3L, "2026-06-01")      // 반응 없음 → 스킵
        ), 5);

        assertThat(out).isPresent();
        assertThat(out.get().sampleSize()).isEqualTo(1);   // id=1만
        assertThat(out.get().series().get(0).avgPct()).isEqualByComparingTo("1.50");
    }

    @Test
    @DisplayName("표본 전무 → Optional.empty (FE 차트 미노출)")
    void forecast_noSamples_returnsEmpty() {
        when(disclosureRepository.findAllById(any())).thenReturn(List.of());
        Optional<PriceReactionForecast> out = service.forecast(List.of(item(9L, "2026-06-01")), 5);
        assertThat(out).isEmpty();
    }

    @Test
    @DisplayName("null·빈 입력 → Optional.empty")
    void forecast_nullOrEmpty_returnsEmpty() {
        assertThat(service.forecast(null, 5)).isEmpty();
        assertThat(service.forecast(List.of(), 5)).isEmpty();
    }
}
