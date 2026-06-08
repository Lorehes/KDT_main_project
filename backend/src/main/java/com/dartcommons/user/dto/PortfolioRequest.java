package com.dartcommons.user.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/*
 * [목적] POST /api/v1/portfolios(등록) + PUT /api/v1/portfolios/{id}(수정) 공통 요청 DTO.
 * [이유] avgBuyPrice·quantity는 BigDecimal로 수신 후 AesGcmEncryptor로 암호화 — 평문이 서비스/리포지토리 계층 이하로 내려가지 않도록 DTO 단에서 차단.
 *       stockCode는 6자리 숫자 패턴 강제(KRX 코드 규격).
 * [사이드 임팩트] PUT(수정) 경로에서는 stockCode가 PortfolioEntity.update()에 전달되지 않아 변경 불가.
 *               stockCode 변경이 필요하면 DELETE 후 새 POST로 처리해야 함.
 * [수정 시 고려사항] avgBuyPrice·quantity에 소수점 자릿수 제한이 필요하면 @Digits 추가.
 *                  매수가 0 허용(무상 취득 케이스)이므로 @DecimalMin("0").
 *                  create/update DTO 분리가 필요하면 PortfolioUpdateRequest(stockCode 없음) 신규 작성.
 */
public record PortfolioRequest(
        @NotBlank @Pattern(regexp = "\\d{6}", message = "종목코드는 6자리 숫자여야 합니다") String stockCode,
        @NotNull @DecimalMin(value = "0", message = "매수가는 0 이상이어야 합니다") BigDecimal avgBuyPrice,
        @NotNull @Positive(message = "수량은 0보다 커야 합니다") BigDecimal quantity,
        @Size(max = 200) String memo
) {}
