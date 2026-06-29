package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDate;

/*
 * [목적] GET /api/v1/portfolios/summary 응답 DTO — 전체 포트폴리오 평가 손익 집계.
 * [이유] avgBuyPrice·quantity 원본을 재노출 없이 합산값만 반환 — 금융 개인정보 최소 노출(CLAUDE.md §7).
 *       totalCostBasis/totalEvalAmount는 close_price가 있는 종목만 대상 — 미수집 종목은 unpricedCount로만 표시.
 * [사이드 임팩트] pnlRate는 totalCostBasis == 0이면 null — 0 나눗셈 방지. FE는 null이면 "—" 표시.
 *               asOf는 priced 종목 중 가장 최근 기준일 — 여러 종목 기준일이 다를 때 기준.
 *               @JsonProperty 필수 — 전역 Jackson SNAKE_CASE 전략 미설정(PortfolioRequest 참조).
 *               @JsonProperty 없으면 camelCase 직렬화 → FE snake_case 필드 전부 undefined.
 * [수정 시 고려사항] 종목별 세부 손익(종목 이름·종가·수익률)이 필요하면 별도 DTO로 분리 권장.
 *                  pnlRate 소수점 자리수(현재 2자리)는 FE 포맷과 협의해 조정.
 */
public record PortfolioSummaryResponse(
        /** 총 매수금액 — close_price 있는 종목의 avgBuyPrice × quantity 합산. */
        @JsonProperty("total_cost_basis")  BigDecimal totalCostBasis,
        /** 총 평가금액 — close_price × quantity 합산. */
        @JsonProperty("total_eval_amount") BigDecimal totalEvalAmount,
        /** 평가 손익 = totalEvalAmount − totalCostBasis. */
        @JsonProperty("total_pnl")         BigDecimal totalPnl,
        /** 수익률(%) = totalPnl / totalCostBasis × 100. totalCostBasis == 0이면 null. */
        @JsonProperty("pnl_rate")          BigDecimal pnlRate,
        /** 종가 수집 완료 종목 수. */
        @JsonProperty("priced_count")      int pricedCount,
        /** 종가 미수집 또는 매수가/수량 미입력 종목 수. */
        @JsonProperty("unpriced_count")    int unpricedCount,
        /** 종가 기준일 — priced 종목 중 최신 거래일. pricedCount == 0이면 null. */
        @JsonProperty("as_of")             LocalDate asOf
) {}
