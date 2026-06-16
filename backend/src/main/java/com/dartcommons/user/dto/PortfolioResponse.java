package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/*
 * [목적] GET/POST/PUT /api/v1/portfolios 응답 DTO — 복호화된 매수가·수량 + 종목명 포함.
 * [이유] avgBuyPrice·quantity는 DB에 AES-256-GCM 암호화 BYTEA로 저장.
 *       응답 시 서비스 계층에서 복호화 후 BigDecimal로 변환해 이 DTO에 담아 반환.
 *       암호화 바이트 배열이 JSON에 노출되지 않도록 PortfolioEntity 대신 이 DTO 사용.
 *       corpName: stocks 마스터에서 JOIN 없이 별도 조회(bulk/단건) — stocks FK 참조 보장으로 null 거의 불가.
 * [사이드 임팩트] record 필드 추가로 PortfolioService.toResponse() 시그니처 전체 변경됨 — 컴파일 시 일괄 확인.
 * [수정 시 고려사항] corpName은 null 허용(stocks 마스터 미등재 엣지케이스 대비) — FE는 nullable 처리.
 *                  손익계산(평가금액·수익률)은 주가 API 데이터 필요 — 향후 확장 필드로 추가 가능.
 */
public record PortfolioResponse(
        Long id,
        @JsonProperty("stock_code")    String stockCode,
        @JsonProperty("corp_name")     String corpName,
        @JsonProperty("avg_buy_price") BigDecimal avgBuyPrice,
        BigDecimal quantity,
        String memo,
        @JsonProperty("created_at")    OffsetDateTime createdAt,
        @JsonProperty("updated_at")    OffsetDateTime updatedAt
) {}
