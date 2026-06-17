package com.dartcommons.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * [목적] POST /api/v1/portfolios(등록) + PUT /api/v1/portfolios/{id}(수정) 공통 요청 DTO.
 * [이유] FE가 snake_case JSON(`stock_code`, `avg_buy_price`)으로 전송하므로 @JsonProperty 매핑 필수.
 *       전역 Jackson SNAKE_CASE 전략 미설정 상태에서 @JsonProperty 없으면 camelCase로만 역직렬화 → stockCode null → @NotBlank 위반 400.
 *       avgBuyPrice·quantity는 BigDecimal로 수신 후 AesGcmEncryptor로 암호화 — 평문이 서비스 계층 이하로 내려가지 않도록 DTO 단에서 차단.
 * [사이드 임팩트] PUT(수정) 경로에서는 stockCode가 PortfolioEntity.update()에 전달되지 않아 변경 불가.
 *               stockCode 변경이 필요하면 DELETE 후 새 POST로 처리해야 함.
 *               avgBuyPrice/quantity가 null인 경우 PortfolioService에서 null byte[] 저장 — 손익계산 화면은 null 표시 처리 필요.
 * [수정 시 고려사항] avgBuyPrice·quantity에 소수점 자릿수 제한이 필요하면 @Digits 추가.
 *                  매수가 0 허용(무상 취득 케이스)이므로 @DecimalMin("0").
 *                  @DecimalMax(avgBuyPrice)·@Max(quantity): FE max 검증과 정합 — 비현실적 값 서버 측 차단.
 *                  Jakarta Validation: @DecimalMin, @Positive는 null 값을 유효로 간주(skip) — @NotNull 없이도 비-null 값만 범위 검증.
 *                  create/update DTO 분리가 필요하면 PortfolioUpdateRequest(stockCode 없음) 신규 작성.
 */
public record PortfolioRequest(
        @JsonProperty("stock_code")
        @NotBlank @Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자여야 합니다") String stockCode,
        @JsonProperty("avg_buy_price")
        @DecimalMin(value = "0", message = "매수가는 0 이상이어야 합니다")
        @DecimalMax(value = "999999999", message = "매수가가 너무 큽니다") BigDecimal avgBuyPrice,
        @Positive(message = "수량은 0보다 커야 합니다")
        @DecimalMax(value = "100000000", message = "수량이 너무 많습니다") BigDecimal quantity,
        @Size(max = 200) String memo
) {}
