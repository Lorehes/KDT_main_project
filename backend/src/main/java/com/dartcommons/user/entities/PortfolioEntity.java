package com.dartcommons.user.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] portfolios 테이블(V3) JPA 엔티티 — 사용자별 보유 종목 + 매수가/수량 AES-256 암호화.
 * [이유] 매수가·수량은 금융 개인정보 — AES-256-GCM BYTEA 저장(db_schema §3.3, CLAUDE.md §7).
 *       DB UNIQUE(user_id, stock_code) + 애플리케이션 계층 Free 3종목 제한 이중 방어.
 * [사이드 임팩트] stock_code는 stocks 테이블 FK(DB 레벨). stock_code 역조회로 공시→영향 사용자 집합 산출(feature_structure §2).
 *               avg_buy_price_enc/quantity_enc는 DB 정렬·연산 불가 — 복호화 후 앱 계층에서만 처리.
 * [수정 시 고려사항] UserEntity는 @ManyToOne 관계 없이 user_id Long으로만 참조 — IDOR 방지는 서비스 계층 책임.
 *                  memo 평문 저장 — 민감정보 포함 여부는 사용자 책임(안내 문구 제공 권장).
 */
@Entity
@Table(name = "portfolios")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PortfolioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "stock_code", nullable = false, length = 6)
    private String stockCode;

    /** AES-256-GCM 암호화 매수가. 평문 저장·로깅 절대 금지(CLAUDE.md §7). */
    @Column(name = "avg_buy_price_enc")
    private byte[] avgBuyPriceEnc;

    /** AES-256-GCM 암호화 수량. 평문 저장·로깅 절대 금지(CLAUDE.md §7). */
    @Column(name = "quantity_enc")
    private byte[] quantityEnc;

    @Column(name = "memo", length = 200)
    private String memo;

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

    public void update(byte[] avgBuyPriceEnc, byte[] quantityEnc, String memo) {
        this.avgBuyPriceEnc = avgBuyPriceEnc;
        this.quantityEnc    = quantityEnc;
        this.memo           = memo;
    }
}
