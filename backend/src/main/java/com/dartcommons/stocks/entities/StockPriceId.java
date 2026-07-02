package com.dartcommons.stocks.entities;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/*
 * [목적] StockPrice 복합 PK (stockCode, tradeDate) — @IdClass 직렬화 타입.
 * [이유] JPA @IdClass 규약: Serializable + no-arg constructor + equals/hashCode.
 *       record는 canonical constructor가 no-arg가 아니라 JPA spec 미보장(Hibernate 6 구현 의존).
 *       일반 클래스로 구현해 spec 준수 보장.
 */
public class StockPriceId implements Serializable {

    private String stockCode;
    private LocalDate tradeDate;

    /** JPA @IdClass 필수 no-arg 생성자. */
    public StockPriceId() {}

    public StockPriceId(String stockCode, LocalDate tradeDate) {
        this.stockCode = stockCode;
        this.tradeDate = tradeDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof StockPriceId other)) return false;
        return Objects.equals(stockCode, other.stockCode) && Objects.equals(tradeDate, other.tradeDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(stockCode, tradeDate);
    }
}
