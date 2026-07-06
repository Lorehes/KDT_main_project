package com.dartcommons.stocks.entities;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/*
 * [목적] financial_snapshots 테이블(V31) 매핑 — DART fnlttSinglAcnt.json 분기 재무 스냅샷.
 *       Stage 5 재무 분석 프롬프트 입력 데이터 소스 (analysis-stage5-financial-industry Spec R3).
 * [이유] stocks 도메인에 배치: 재무는 corp_code 기준 마스터성 데이터 — 공시 분석 이외 확장 여지.
 *       CLAUDE.md §3-2 마스터 데이터 도메인 예외 적용: analysis 도메인이 read-only로 직접 의존 가능.
 *       Stock 엔티티와 동일 패키지 — StockMasterSyncJob·FinancialSyncJob이 함께 관리.
 * [사이드 임팩트] (corp_code, bsns_year, reprt_code) UNIQUE — FinancialSyncService가 UPSERT로 멱등 저장.
 *               total_assets NOT NULL 제약 — 계정 매칭 실패(금융업 등) 시 레코드 미생성.
 *               analysis 도메인 Stage5Analyzer는 repo를 read-only로 직접 참조(CLAUDE.md §3-2 예외).
 * [수정 시 고려사항] ddl-auto: validate — 컬럼 추가는 새 Flyway 마이그레이션 + 이 파일 동시 수정.
 *                  전기(frmtrm_amount) 저장 불필요: 당해연도·전년도 두 행을 비교해 증감 계산.
 */
@Entity
@Table(name = "financial_snapshots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FinancialSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corp_code", nullable = false, length = 8)
    private String corpCode;

    @Column(name = "bsns_year", nullable = false, length = 4)
    private String bsnsYear;

    @Column(name = "reprt_code", nullable = false, length = 5)
    private String reprtCode;

    @Column(name = "fs_div", nullable = false, length = 3)
    private String fsDiv;

    @Column(name = "revenue", precision = 22)
    private BigDecimal revenue;

    @Column(name = "op_profit", precision = 22)
    private BigDecimal opProfit;

    @Column(name = "net_income", precision = 22)
    private BigDecimal netIncome;

    @Column(name = "total_assets", nullable = false, precision = 22)
    private BigDecimal totalAssets;

    @Column(name = "total_liab", precision = 22)
    private BigDecimal totalLiab;

    @Column(name = "total_equity", precision = 22)
    private BigDecimal totalEquity;

    @Column(name = "fetched_at", nullable = false, updatable = false)
    private OffsetDateTime fetchedAt;

    @PrePersist
    private void prePersist() {
        if (fetchedAt == null) fetchedAt = OffsetDateTime.now();
    }
}
