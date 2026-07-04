package com.dartcommons.user.services;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * [목적] PortfolioService.parseSafe() — NFE 경로 + null 경로 단위 검증.
 * [이유] parseSafe는 복호화 값의 BigDecimal 변환 실패 시 PII(원문 숫자)를 예외 메시지에 노출하지 않아야 함(CLAUDE.md §7).
 *       private → package-private으로 변경하여 동일 패키지 단위 테스트 가능.
 * [사이드 임팩트] Testcontainers 불필요 — 순수 로직 테스트.
 * [수정 시 고려사항] parseSafe 이동 시 이 테스트 패키지도 동기화 필요.
 */
class PortfolioServiceParseSafeTest {

    @Test
    @DisplayName("parseSafe(null) → null 반환")
    void parseSafe_null_returnsNull() {
        assertThat(PortfolioService.parseSafe(null)).isNull();
    }

    @Test
    @DisplayName("parseSafe(비숫자) → IllegalStateException, 메시지에 원문 미포함(금융 PII 보호)")
    void parseSafe_invalidString_throwsIllegalState_withoutPii() {
        assertThatThrownBy(() -> PortfolioService.parseSafe("NOT_A_NUMBER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("NOT_A_NUMBER");
    }

    @Test
    @DisplayName("parseSafe(유효 숫자 문자열) → BigDecimal 반환")
    void parseSafe_validString_returnsBigDecimal() {
        BigDecimal result = PortfolioService.parseSafe("50000");
        assertThat(result).isEqualByComparingTo(new BigDecimal("50000"));
    }
}
