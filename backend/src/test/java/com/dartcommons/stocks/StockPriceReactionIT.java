package com.dartcommons.stocks;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.stocks.services.StockPriceProvider;
import com.dartcommons.stocks.services.StockPriceProvider.PriceReaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] StockPriceService.findReactionSeries 실 SQL/등락률 math 검증 — 기준가(on-or-before) + D+1~D+N % 계산.
 * [이유] 예측 차트의 핵심 산술 — Mock DB 금지(CLAUDE.md §6-6), 실 stock_prices(V27)로 검증.
 * [사이드 임팩트] 공유 stock_prices에 삽입 — @BeforeEach 정리.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key", "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key", "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin", "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
})
class StockPriceReactionIT {

    @MockitoBean DisclosurePollingJob pollingJob;

    @Autowired StockPriceProvider stockPriceProvider;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        jdbcTemplate.update("DELETE FROM stock_prices");
    }

    private void insert(String code, String date, String price) {
        jdbcTemplate.update("INSERT INTO stock_prices (stock_code, trade_date, close_price) VALUES (?, ?::date, ?)",
                code, date, new java.math.BigDecimal(price));
    }

    @Test
    @DisplayName("findReactionSeries — D0 종가 대비 D+1~D+5 등락률(%) 정확 계산")
    void findReactionSeries_computesPctFromBaseline() {
        // 005930(시드 커버 종목). D0=06-01 기준가 10000
        insert("005930", "2026-06-01", "10000");
        insert("005930", "2026-06-02", "10200");  // +2.00%
        insert("005930", "2026-06-03", "9900");   // -1.00%
        insert("005930", "2026-06-04", "10500");  // +5.00%
        insert("005930", "2026-06-05", "10000");  //  0.00%
        insert("005930", "2026-06-08", "9500");   // -5.00% (06-06/07 주말 갭 — 다음 거래일)

        List<PriceReaction> series = stockPriceProvider.findReactionSeries("005930", LocalDate.parse("2026-06-01"), 5);

        assertThat(series).hasSize(5);
        assertThat(series.get(0).day()).isEqualTo(1);
        assertThat(series.get(0).pct()).isEqualByComparingTo("2.00");
        assertThat(series.get(1).pct()).isEqualByComparingTo("-1.00");
        assertThat(series.get(2).pct()).isEqualByComparingTo("5.00");
        assertThat(series.get(3).pct()).isEqualByComparingTo("0.00");
        assertThat(series.get(4).pct()).isEqualByComparingTo("-5.00");  // 06-08이 D+5(거래일 순서)
    }

    @Test
    @DisplayName("findReactionSeries — baseDate에 행 없으면 이전 최신 거래일을 기준가로(공휴일 접수 대비)")
    void findReactionSeries_baselineOnOrBefore() {
        insert("005930", "2026-05-30", "10000");  // 금요일 종가 = 기준가
        insert("005930", "2026-06-02", "11000");  // D+1 = +10.00%

        // 06-01(월, 가정상 행 없음)로 조회 → 기준가는 05-30
        List<PriceReaction> series = stockPriceProvider.findReactionSeries("005930", LocalDate.parse("2026-06-01"), 5);

        assertThat(series).hasSize(1);
        assertThat(series.get(0).pct()).isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("findReactionSeries — 기준가 없으면 빈 리스트(추측 금지)")
    void findReactionSeries_noBaseline_returnsEmpty() {
        insert("005930", "2026-06-02", "11000");  // D0(06-01) 이전 종가 없음
        List<PriceReaction> series = stockPriceProvider.findReactionSeries("005930", LocalDate.parse("2026-06-01"), 5);
        assertThat(series).isEmpty();
    }
}
