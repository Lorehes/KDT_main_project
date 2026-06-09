package com.dartcommons.analysis;

import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.shared.enums.Sentiment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] AnalysisResponse.from() 티어별 필드 화이트리스트 단위 테스트.
 * [이유] be-api-blocking-bugs-fix R5(financial_context dead code 수정) + 자본시장법 §11.1 disclaimer 회귀 방지.
 *       각 티어별 노출/비노출 필드를 명시적으로 검증해 향후 티어 로직 변경 시 의도치 않은 노출 차단.
 * [사이드 임팩트] 없음 — DTO 단위 테스트, DB/네트워크 의존 없음.
 * [수정 시 고려사항] Stage 5 구현 시 financial_context 필드가 null → non-null로 바뀌므로 PREMIUM 테스트 케이스 갱신 필요.
 *                  disclaimer 문구 변경은 법무 검수 필요(CLAUDE.md §6-6).
 */
class AnalysisResponseTest {

    private AnalysisResult ar;

    @BeforeEach
    void setup() {
        ar = AnalysisResult.builder()
                .disclosureId(100L)
                .sentiment(Sentiment.POSITIVE)
                .confidence(new BigDecimal("0.850"))
                .withheld(false)
                .summary("긍정적인 공시 내용입니다.")
                .expectedReaction(AnalysisResult.ExpectedReaction.UP)
                .rationale("과거 유사 공시 분석 근거")
                .stageReached((short) 2)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    @Test
    @DisplayName("FREE 티어 — sentiment·confidence·summary·disclaimer 포함, expected_reaction·rationale null")
    void from_freeTier_includesBaseFieldsOnly() {
        AnalysisResponse resp = AnalysisResponse.from(ar, AnalysisResponse.Tier.FREE);

        assertThat(resp.sentiment()).isEqualTo(Sentiment.POSITIVE);
        assertThat(resp.confidence()).isEqualByComparingTo(new BigDecimal("0.850"));
        assertThat(resp.summary()).isEqualTo("긍정적인 공시 내용입니다.");
        assertThat(resp.isWithheld()).isFalse();
        assertThat(resp.disclaimer()).isEqualTo(AnalysisResponse.DISCLAIMER);
        assertThat(resp.reportInaccuracyPath()).contains("/analyses/");

        // Pro+ 필드 제외
        assertThat(resp.expectedReaction()).isNull();
        assertThat(resp.rationale()).isNull();
        // Stage 5 미구현 — 모든 티어에서 null
        assertThat(resp.financialContext()).isNull();
    }

    @Test
    @DisplayName("PRO 티어 — expected_reaction·rationale 포함, financial_context null(Stage 5 미구현)")
    void from_proTier_includesProFields() {
        AnalysisResponse resp = AnalysisResponse.from(ar, AnalysisResponse.Tier.PRO);

        assertThat(resp.expectedReaction()).isEqualTo(AnalysisResult.ExpectedReaction.UP);
        assertThat(resp.rationale()).isEqualTo("과거 유사 공시 분석 근거");
        assertThat(resp.disclaimer()).isEqualTo(AnalysisResponse.DISCLAIMER);
        // Stage 5 미구현
        assertThat(resp.financialContext()).isNull();
    }

    @Test
    @DisplayName("PREMIUM 티어 — Pro+ 필드 포함, financial_context는 Stage 5 미구현으로 null")
    void from_premiumTier_includesProFieldsAndFinancialContextNull() {
        AnalysisResponse resp = AnalysisResponse.from(ar, AnalysisResponse.Tier.PREMIUM);

        assertThat(resp.expectedReaction()).isEqualTo(AnalysisResult.ExpectedReaction.UP);
        assertThat(resp.rationale()).isNotNull();
        // TODO Stage-5: 구현 후 non-null 검증으로 교체
        assertThat(resp.financialContext()).isNull();
        assertThat(resp.disclaimer()).isEqualTo(AnalysisResponse.DISCLAIMER);
    }

    @Test
    @DisplayName("모든 티어 — disclaimer + report_inaccuracy_path 항상 포함 (자본시장법 §11.1 회귀 게이트)")
    void from_allTiers_alwaysIncludeDisclaimerAndReportPath() {
        for (AnalysisResponse.Tier tier : AnalysisResponse.Tier.values()) {
            AnalysisResponse resp = AnalysisResponse.from(ar, tier);

            assertThat(resp.disclaimer())
                    .as("tier=%s: disclaimer must be present", tier)
                    .isNotNull()
                    .isNotBlank();
            assertThat(resp.reportInaccuracyPath())
                    .as("tier=%s: report_inaccuracy_path must be present", tier)
                    .isNotNull()
                    .contains("/analyses/");
        }
    }

    @Test
    @DisplayName("is_withheld=true — 모든 티어에서 그대로 전달")
    void from_withheld_propagatedToAllTiers() {
        AnalysisResult withheldAr = AnalysisResult.builder()
                .disclosureId(200L)
                .sentiment(Sentiment.NEUTRAL)
                .confidence(new BigDecimal("0.300"))
                .withheld(true)
                .summary("판단 보류 케이스")
                .stageReached((short) 2)
                .createdAt(OffsetDateTime.now())
                .build();

        for (AnalysisResponse.Tier tier : AnalysisResponse.Tier.values()) {
            AnalysisResponse resp = AnalysisResponse.from(withheldAr, tier);
            assertThat(resp.isWithheld())
                    .as("tier=%s: is_withheld must be true", tier)
                    .isTrue();
        }
    }
}
