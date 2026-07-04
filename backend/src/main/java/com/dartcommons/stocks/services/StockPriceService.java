package com.dartcommons.stocks.services;

import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.entities.StockPrice;
import com.dartcommons.stocks.repositories.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/*
 * [목적] StockPriceProvider 구현체 — stocks 테이블의 close_price/price_asof 컬럼(접근법 A MVP)을 읽어
 *       PriceInfo로 변환. StockMasterService 캐시(stockByCode·stocksByCodeIn TTL 4h) 경유.
 * [이유] dashboard-eval-pnl Tech Review 카드 #5: PortfolioService가 이 인터페이스만 의존하면
 *       Stage 5 착수 시 stock_prices 시계열 테이블(접근법 B)로 교체해도 PortfolioService 무변경.
 * [사이드 임팩트] StockMasterService 캐시를 거치므로 KrxPriceSyncJob 완료 후 캐시 evict가 필수.
 *               evict 없으면 TTL(4h) 만료까지 stale 종가가 평가 손익에 반영됨.
 * [수정 시 고려사항] 접근법 B 전환 시 이 클래스만 교체 — StockRepository stock_prices 조회로 변경.
 *                  closePrice == null인 종목은 결과 Map에서 제외 → unpricedCount 증가 원인.
 */
@Service
@RequiredArgsConstructor
public class StockPriceService implements StockPriceProvider {

    private final StockMasterService stockMasterService;
    private final StockPriceRepository stockPriceRepository;

    @Override
    public Optional<PriceInfo> findLatestPrice(String stockCode) {
        return stockMasterService.findByStockCode(stockCode)
                .filter(s -> s.getClosePrice() != null)
                .map(s -> new PriceInfo(s.getClosePrice(), s.getPriceAsof()));
    }

    @Override
    public Map<String, PriceInfo> findLatestPrices(Collection<String> stockCodes) {
        return stockMasterService.findByStockCodeIn(stockCodes).stream()
                .filter(s -> s.getClosePrice() != null)
                .collect(Collectors.toMap(
                        Stock::getStockCode,
                        s -> new PriceInfo(s.getClosePrice(), s.getPriceAsof())
                ));
    }

    /*
     * [목적] stock_prices 시계열에서 D0 대비 D+1~D+days 등락률(%) 산출 — 예측 차트 데이터(Wave C).
     * [이유] 기준가는 baseDate 이전 최신 거래일 종가(공휴일 접수 대비). 이후 거래일 종가와 % 비교.
     *       추측 금지(CLAUDE.md §6-6): 데이터 없으면 빈 리스트/짧은 리스트 반환 — 없는 봉을 만들지 않음.
     * [사이드 임팩트] stock_prices 미적재 종목·구간은 빈 결과 → 상위(PriceReactionForecastService)가 sampleSize에서 제외.
     * [수정 시 고려사항] days 확장 시 findReactionPrices Pageable limit만 조정. 수정주가 미보정(raw) — 분할 구간 왜곡 가능.
     */
    @Override
    public List<PriceReaction> findReactionSeries(String stockCode, java.time.LocalDate baseDate, int days) {
        if (days <= 0) return List.of();
        // 기준가 = baseDate 이전(inclusive) 최신 종가 1건
        List<StockPrice> base = stockPriceRepository.findLatestOnOrBefore(stockCode, baseDate, PageRequest.of(0, 1));
        if (base.isEmpty()) return List.of();
        BigDecimal basePrice = base.get(0).getClosePrice();
        if (basePrice.signum() <= 0) return List.of();  // 0 나눗셈 방지

        // baseDate 다음 거래일부터 최대 days개
        List<StockPrice> forward = stockPriceRepository.findReactionPrices(
                stockCode, baseDate.plusDays(1), PageRequest.of(0, days));

        List<PriceReaction> out = new ArrayList<>(forward.size());
        for (int i = 0; i < forward.size(); i++) {
            BigDecimal pct = forward.get(i).getClosePrice().subtract(basePrice)
                    .divide(basePrice, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
            out.add(new PriceReaction(i + 1, pct));  // D+(i+1)
        }
        return out;
    }
}
