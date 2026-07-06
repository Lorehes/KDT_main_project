package com.dartcommons.infrastructure.dart;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] DartFinancialClient.parseAmount 정적 파서 단위 테스트 — DART 금액 문자열 실측 포맷 검증.
 * [이유] 실측(2026-07-06): thstrm_amount는 콤마 포함 문자열("227,062,266,000,000"),
 *       미보고 시 "-" 또는 빈 문자열. 파싱 오류가 재무 수치 왜곡으로 직결되므로 경계값 고정.
 * [사이드 임팩트] 없음 — 순수 정적 메서드, DB/네트워크 의존 없음.
 * [수정 시 고려사항] DART 응답 포맷 변경 시 이 테스트가 회귀 감지 게이트.
 */
class DartFinancialClientTest {

    @Test
    @DisplayName("콤마 포함 금액 문자열 → BigDecimal (실측 포맷)")
    void parsesCommaSeparatedAmount() {
        assertThat(DartFinancialClient.parseAmount("227,062,266,000,000"))
                .isEqualByComparingTo(new BigDecimal("227062266000000"));
    }

    @Test
    @DisplayName("음수 금액 (당기순손실)")
    void parsesNegativeAmount() {
        assertThat(DartFinancialClient.parseAmount("-1,234,567"))
                .isEqualByComparingTo(new BigDecimal("-1234567"));
    }

    @Test
    @DisplayName("미보고 '-' → null")
    void dashReturnsNull() {
        assertThat(DartFinancialClient.parseAmount("-")).isNull();
    }

    @Test
    @DisplayName("빈 문자열·null·공백 → null")
    void blankReturnsNull() {
        assertThat(DartFinancialClient.parseAmount("")).isNull();
        assertThat(DartFinancialClient.parseAmount("   ")).isNull();
        assertThat(DartFinancialClient.parseAmount(null)).isNull();
    }

    @Test
    @DisplayName("숫자 아닌 문자열 → null (예외 아닌 안전 처리)")
    void invalidReturnsNull() {
        assertThat(DartFinancialClient.parseAmount("N/A")).isNull();
    }
}
