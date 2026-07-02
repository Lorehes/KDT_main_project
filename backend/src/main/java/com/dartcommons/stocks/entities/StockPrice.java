package com.dartcommons.stocks.entities;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/*
 * [목적] stock_prices(V27) 엔티티 — KRX 일별 종가 시계열 1행.
 *       PK(stockCode, tradeDate)가 멱등 upsert 키 + 반응 조회 인덱스를 겸한다.
 * [이유] stocks.close_price(최신 종가 1건)와 병행하는 접근법 B 시계열 계층(StockPriceProvider seam).
 *       공개 시세라 평문 저장(암호화 불필요).
 * [사이드 임팩트] KrxPriceSyncJob이 당일 INSERT(ON CONFLICT DO NOTHING), PriceBackfillService가 과거 배치 INSERT.
 *               StockPriceRepository.findReactionPrices()가 D+1~D+5 반응 산출에 사용(Wave C).
 * [수정 시 고려사항] 수정주가 보정 도입 시 adjusted_close_price 필드 + V{n} 마이그레이션 추가.
 *                  IdClass 대신 @EmbeddedId도 가능하나 IdClass가 QueryDSL·Repository 코드 단순.
 */
@Entity
@Table(name = "stock_prices")
@IdClass(StockPriceId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class StockPrice {

    @Id
    @Column(name = "stock_code", length = 6, nullable = false)
    private String stockCode;

    @Id
    @Column(name = "trade_date", nullable = false)
    private LocalDate tradeDate;

    @Column(name = "close_price", precision = 20, scale = 4, nullable = false)
    private BigDecimal closePrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
