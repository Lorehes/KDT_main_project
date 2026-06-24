package com.dartcommons.stocks.services;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/*
 * [목적] 종목 현재가(종가) 조회 인터페이스 — 저장소 교체 격리 seam.
 *       현재 구현(StockPriceService)은 stocks 테이블 컬럼(접근법 A).
 *       Stage 5 착수 시 stock_prices 시계열 테이블(접근법 B)로 교체 가능 — PortfolioService는 변경 없음.
 * [이유] dashboard-eval-pnl Tech Review 카드 #5: A→B 승격 시 영향 범위를 StockPriceService 내부로 한정.
 *       user 도메인(PortfolioService)이 stocks 도메인을 read-only로 의존 — CLAUDE.md §3-2 마스터 예외.
 * [사이드 임팩트] 이 인터페이스를 변경하면 StockPriceService·PortfolioService 모두 영향.
 *               findLatestPrices()는 bulk 조회용 — N+1 방지.
 * [수정 시 고려사항] 접근법 B 전환 시 이 인터페이스 변경 없이 StockPriceService만 교체.
 *                  실시간 시세가 필요하면 별도 인터페이스(RealtimePriceProvider)로 분리 권장.
 */
public interface StockPriceProvider {

    /**
     * 단건 종목 최신 종가 조회.
     *
     * @param stockCode KRX 6자리 종목코드
     * @return 종가 정보. NULL이면 미수집.
     */
    Optional<PriceInfo> findLatestPrice(String stockCode);

    /**
     * 다건 종목 최신 종가 일괄 조회 — N+1 방지용 bulk fetch.
     *
     * @param stockCodes 조회할 종목코드 집합
     * @return stockCode → PriceInfo 매핑. 미수집 종목은 포함되지 않음.
     */
    Map<String, PriceInfo> findLatestPrices(Collection<String> stockCodes);

    /** 종가 + 기준일 컨테이너 — 신선도 표시용. */
    record PriceInfo(BigDecimal closePrice, LocalDate priceAsof) {}
}
