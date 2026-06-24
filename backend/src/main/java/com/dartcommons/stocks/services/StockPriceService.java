package com.dartcommons.stocks.services;

import com.dartcommons.stocks.entities.Stock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
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
}
