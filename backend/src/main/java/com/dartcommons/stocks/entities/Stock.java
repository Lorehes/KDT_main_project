package com.dartcommons.stocks.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] stocks 테이블(V2)을 매핑하는 JPA 엔티티 — 코스피200+코스닥150 종목 마스터.
 *       DART corp_code ↔ KRX stock_code 정규화 기준 데이터.
 * [이유] 공시 커버 필터(DisclosureCollectionService)와 portfolios FK가 공통 참조하는 마스터.
 *       Stock 엔티티 도입으로 disclosure의 JdbcTemplate native query를 제거하고
 *       N+1 이슈(deferred HIGH)를 StockRepository.findAllStockCodes() Set 1회 로드로 해결.
 * [사이드 임팩트] V3 portfolios·V4 disclosures가 stock_code를 FK로 참조 — 삭제 시 RESTRICT.
 *               분기 리밸런싱(코스피200/코스닥150 편입제외)으로 행 추가/삭제 가능 — portfolios 잔존
 *               레코드 처리 정책은 후속 Spec(사용자 알림 + 마이그레이션).
 * [수정 시 고려사항] ddl-auto: validate로 V2 스키마와 정합 가드 — 컬럼 추가는 새 Flyway 마이그레이션.
 *                  corp_name은 KRX/DART 원본 — 사용자 표시용 변형 금지(CLAUDE.md §4).
 *                  market은 V2 CHECK 제약(KOSPI/KOSDAQ/KONEX) — enum 도입 시 DB 제약과 동기화 필요.
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

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

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
