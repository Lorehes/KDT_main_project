package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/*
 * [목적] GET/POST/PUT /api/v1/portfolios 응답 DTO — 복호화된 매수가·수량 포함.
 * [이유] avgBuyPrice·quantity는 DB에 AES-256-GCM 암호화 BYTEA로 저장.
 *       응답 시 서비스 계층에서 복호화 후 BigDecimal로 변환해 이 DTO에 담아 반환.
 *       암호화 바이트 배열이 JSON에 노출되지 않도록 PortfolioEntity 대신 이 DTO 사용.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] 손익계산(평가금액·수익률)은 주가 API 데이터 필요 — 향후 확장 필드로 추가 가능.
 */
public record PortfolioResponse(
        Long id,
        @JsonProperty("stock_code")    String stockCode,
        @JsonProperty("avg_buy_price") BigDecimal avgBuyPrice,
        BigDecimal quantity,
        String memo,
        @JsonProperty("created_at")    OffsetDateTime createdAt,
        @JsonProperty("updated_at")    OffsetDateTime updatedAt
) {}
