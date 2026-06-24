package com.dartcommons.stocks.entities;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/*
 * [목적] stocks 테이블(V2+V23)을 매핑하는 JPA 엔티티 — 코스피200+코스닥150 종목 마스터.
 *       V23에서 close_price·price_asof 추가 — KrxPriceSyncJob 일배치 적재(dashboard-eval-pnl).
 * [이유] 공시 커버 필터(DisclosureCollectionService)와 portfolios FK가 공통 참조하는 마스터.
 *       Stock 엔티티 도입으로 disclosure의 JdbcTemplate native query를 제거하고
 *       N+1 이슈(deferred HIGH)를 StockRepository.findAllStockCodes() Set 1회 로드로 해결.
 * [사이드 임팩트] V3 portfolios·V4 disclosures가 stock_code를 FK로 참조 — 삭제 시 RESTRICT.
 *               분기 리밸런싱(코스피200/코스닥150 편입제외)으로 행 추가/삭제 가능.
 *               stockByCode·stocksByCodeIn Caffeine 캐시(TTL 4h)가 이 엔티티를 캐시 —
 *               KrxPriceSyncJob 완료 후 반드시 해당 캐시를 evict해야 stale 현재가 노출 방지.
 * [수정 시 고려사항] ddl-auto: validate — 컬럼 추가는 새 Flyway 마이그레이션 + 이 파일 동시 수정.
 *                  corp_name은 KRX/DART 원본 — 사용자 표시용 변형 금지(CLAUDE.md §4).
 *                  Stage 5(주가 5일 반응) 착수 시 stock_prices 시계열 테이블(접근법 B)로 승격 검토.
 *                  updatePrice()는 JPA dirty-checking 경유 — 동일 트랜잭션 내에서만 호출할 것.
 */
@Entity
@Table(name = "stocks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Stock {

    /** KRX 종목코드(6자리). */
    @Id
    @Column(name = "stock_code", length = 6)
    private String stockCode;

    /** DART 고유번호(8자리). UNIQUE. */
    @Column(name = "corp_code", nullable = false, unique = true, length = 8)
    private String corpCode;

    /** KRX/DART 원본 — 사용자 표시용 변형 금지. */
    @Column(name = "corp_name", nullable = false, length = 100)
    private String corpName;

    /** V2 CHECK: KOSPI / KOSDAQ / KONEX. */
    @Column(name = "market", length = 10)
    private String market;

    @Column(name = "sector", length = 100)
    private String sector;

    /** 최신 종가 — KrxPriceSyncJob 일배치 적재. NULL=미수집(비거래일·배치 실패 포함). 평문 저장 가능(공개 시세). */
    @Column(name = "close_price", precision = 20, scale = 4)
    private BigDecimal closePrice;

    /** 종가 기준 거래일(KST). NULL=미수집. */
    @Column(name = "price_asof")
    private LocalDate priceAsof;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** 일배치 종가 갱신 — KrxPriceSyncJob이 트랜잭션 내에서 호출. dirty-checking으로 UPDATE 발생. */
    public void updatePrice(BigDecimal closePrice, LocalDate priceAsof) {
        this.closePrice = closePrice;
        this.priceAsof  = priceAsof;
    }

    @PrePersist
    private void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
