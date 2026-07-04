package com.dartcommons.user.dto;

/*
 * [목적] POST /api/v1/portfolios/import 요청 DTO — CSV에서 파싱한 종목코드 목록 수신.
 * [이유] 전역 Jackson SNAKE_CASE 설정이 없으므로 @JsonProperty로 stock_codes → stockCodes 명시 매핑.
 *       @NotEmpty + @Size(max=50)는 컨트롤러 @Valid에서 검증 — 50개 초과·빈 목록은 400 즉시 반환.
 * [사이드 임팩트] 없음 — 단순 입력 래퍼 record.
 * [수정 시 고려사항] 한도 50개를 변경하면 Spec(portfolio-csv-bulk-import.md)·FE 유효성 메시지·통합테스트도 동시 갱신.
 *                  avg_buy_price·quantity 필드 추가는 CLAUDE.md §7 금지(금융 PII) — 절대 추가하지 말 것.
 */
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record BulkImportRequest(
        @JsonProperty("stock_codes")
        @NotEmpty(message = "종목코드 목록이 비어있습니다")
        @Size(max = 50, message = "종목코드는 최대 50개까지 등록 가능합니다")
        List<String> stockCodes
) {}
