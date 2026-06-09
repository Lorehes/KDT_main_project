package com.dartcommons.user;

import com.dartcommons.user.dto.PortfolioRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] PortfolioRequest 유효성 검증 단위 테스트 — avgBuyPrice·quantity optional + stockCode 필수 확인.
 * [이유] be-api-blocking-bugs-fix R4: @NotNull 제거 후 null 값이 유효하고(DB nullable BYTEA 저장),
 *       비-null 음수/0 값은 여전히 @DecimalMin·@Positive로 거부되는지 검증.
 *       Jakarta Validation spec: @DecimalMin·@Positive는 null을 유효값으로 간주(skip) — @NotNull만 null을 거부.
 * [사이드 임팩트] 없음 — DTO 단위 테스트, DB/네트워크 의존 없음.
 * [수정 시 고려사항] avgBuyPrice/quantity를 다시 필수로 바꾸면 이 테스트가 실패하며 의도를 알 수 있음 — 회귀 게이트.
 */
class PortfolioRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setup() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    private Set<String> violationPaths(PortfolioRequest req) {
        return validator.validate(req).stream()
                .map(v -> v.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    @DisplayName("avgBuyPrice=null, quantity=null — 유효 (FE optional 필드)")
    void avgBuyPrice_and_quantity_null_passesValidation() {
        PortfolioRequest req = new PortfolioRequest("005930", null, null, null);
        Set<ConstraintViolation<PortfolioRequest>> violations = validator.validate(req);
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("stockCode=null — @NotBlank 위반 400")
    void stockCode_null_failsNotBlank() {
        PortfolioRequest req = new PortfolioRequest(null, new BigDecimal("50000"), new BigDecimal("10"), null);
        assertThat(violationPaths(req)).contains("stockCode");
    }

    @Test
    @DisplayName("stockCode=blank — @NotBlank 위반 400")
    void stockCode_blank_failsNotBlank() {
        PortfolioRequest req = new PortfolioRequest("  ", new BigDecimal("50000"), new BigDecimal("10"), null);
        assertThat(violationPaths(req)).contains("stockCode");
    }

    @Test
    @DisplayName("stockCode='12345' — @Pattern 위반 (6자리 숫자 아님) 400")
    void stockCode_fiveDigits_failsPattern() {
        PortfolioRequest req = new PortfolioRequest("12345", new BigDecimal("50000"), new BigDecimal("10"), null);
        assertThat(violationPaths(req)).contains("stockCode");
    }

    @Test
    @DisplayName("avgBuyPrice=-1 — @DecimalMin 위반 400")
    void avgBuyPrice_negative_failsDecimalMin() {
        PortfolioRequest req = new PortfolioRequest("005930", new BigDecimal("-1"), new BigDecimal("10"), null);
        assertThat(violationPaths(req)).contains("avgBuyPrice");
    }

    @Test
    @DisplayName("avgBuyPrice=0 — @DecimalMin(0) 허용 (무상 취득)")
    void avgBuyPrice_zero_passesDecimalMin() {
        PortfolioRequest req = new PortfolioRequest("005930", BigDecimal.ZERO, new BigDecimal("10"), null);
        assertThat(violationPaths(req)).doesNotContain("avgBuyPrice");
    }

    @Test
    @DisplayName("quantity=0 — @Positive 위반 400")
    void quantity_zero_failsPositive() {
        PortfolioRequest req = new PortfolioRequest("005930", new BigDecimal("50000"), BigDecimal.ZERO, null);
        assertThat(violationPaths(req)).contains("quantity");
    }

    @Test
    @DisplayName("quantity=-5 — @Positive 위반 400")
    void quantity_negative_failsPositive() {
        PortfolioRequest req = new PortfolioRequest("005930", new BigDecimal("50000"), new BigDecimal("-5"), null);
        assertThat(violationPaths(req)).contains("quantity");
    }

    @Test
    @DisplayName("전체 유효 요청 — 모든 필드 비-null 정상값")
    void allFieldsValid_noViolations() {
        PortfolioRequest req = new PortfolioRequest("005930", new BigDecimal("50000.5"), new BigDecimal("10"), "테스트 메모");
        assertThat(validator.validate(req)).isEmpty();
    }
}
