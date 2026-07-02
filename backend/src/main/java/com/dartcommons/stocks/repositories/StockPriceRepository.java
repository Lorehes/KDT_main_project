package com.dartcommons.stocks.repositories;

import com.dartcommons.stocks.entities.StockPrice;
import com.dartcommons.stocks.entities.StockPriceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/*
 * [목적] stock_prices 시계열 CRUD + 멱등 upsert + D+N 반응 조회.
 * [이유] KrxPriceSyncJob·PriceBackfillService가 upsertPrice()를 공유 — ON CONFLICT DO NOTHING으로
 *       일배치 중복·백필 재실행 안전(krx-price-timeseries Spec 확정 결정).
 *       findReactionPrices()는 Wave C StockPriceService.findReactionSeries()가 소비.
 * [사이드 임팩트] @Modifying NativeQuery — Spring Data 캐시 클리어(@Modifying clearAutomatically=true 기본).
 *               upsertPrice는 엔티티 저장 대신 JDBC native INSERT — JPA 1차 캐시 갱신 없음.
 *               같은 트랜잭션에서 findById()를 바로 호출하면 캐시 미갱신으로 stale 결과 가능(실사용 없음).
 * [수정 시 고려사항] 전종목 확대 또는 커버 종목 증가 시 saveAll() bulk INSERT 검토(현재 행당 1 upsert).
 *                  findReactionPrices()의 LIMIT은 Wave C에서 days+1(D0 포함)로 호출됨 — Spec 변경 시 조정.
 */
public interface StockPriceRepository extends JpaRepository<StockPrice, StockPriceId> {

    /**
     * 단일 행 멱등 upsert — ON CONFLICT(stock_code, trade_date) DO NOTHING.
     * 동일 (종목, 날짜)가 이미 존재하면 무시. 백필 재실행·일배치 중복 안전.
     */
    @Modifying
    @Transactional  // @Modifying NativeQuery는 트랜잭션 필수 — 호출자(syncPrices/backfill) TX 전파되나 독립 호출 시 예외 방지
    @Query(nativeQuery = true, value = """
            INSERT INTO stock_prices (stock_code, trade_date, close_price)
            VALUES (:stockCode, :tradeDate, :closePrice)
            ON CONFLICT (stock_code, trade_date) DO NOTHING
            """)
    void upsertPrice(@Param("stockCode") String stockCode,
                     @Param("tradeDate") LocalDate tradeDate,
                     @Param("closePrice") BigDecimal closePrice);

    /**
     * D0 이후 거래일 종가 조회 (D+1~D+days 반응 산출용).
     * ORDER BY trade_date ASC + LIMIT으로 N번째 거래일 행을 순서대로 반환.
     * D0 종가(반응 기준)도 포함(days+1 호출)하여 호출 측에서 D0를 분리.
     */
    @Query("""
            SELECT sp FROM StockPrice sp
            WHERE sp.stockCode = :stockCode
              AND sp.tradeDate >= :fromDate
            ORDER BY sp.tradeDate ASC
            """)
    List<StockPrice> findReactionPrices(@Param("stockCode") String stockCode,
                                        @Param("fromDate") LocalDate fromDate,
                                        org.springframework.data.domain.Pageable pageable);

    /** 해당 날짜 이전(inclusive)의 최신 종가 1건 — D0 종가(기준가) 조회. */
    @Query("""
            SELECT sp FROM StockPrice sp
            WHERE sp.stockCode = :stockCode
              AND sp.tradeDate <= :asof
            ORDER BY sp.tradeDate DESC
            """)
    List<StockPrice> findLatestOnOrBefore(@Param("stockCode") String stockCode,
                                          @Param("asof") LocalDate asof,
                                          org.springframework.data.domain.Pageable pageable);
}
