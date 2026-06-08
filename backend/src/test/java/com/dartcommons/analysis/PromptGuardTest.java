package com.dartcommons.analysis;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.analysis.services.PromptGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] PromptGuard 단위 검증 — 자본시장법 금지 키워드(L2) + 신뢰도 임계 + 240자 cap.
 * [이유] CLAUDE.md §7 / Spec §2.3 L1·L2·L3 — 응답 후처리는 LLM 환각/표현 위반의 마지막 방어선.
 *       Stage2Analyzer 의존이 없는 순수 단위 테스트(스프링 컨텍스트 불필요).
 * [사이드 임팩트] FORBIDDEN_PATTERNS 추가 시 본 테스트도 동기 보강 필요.
 * [수정 시 고려사항] 한글 마침표/물음표/느낌표 경계 분할은 capLength의 핵심 동작 — 시나리오 보존.
 */
class PromptGuardTest {

    private final PromptGuard guard = new PromptGuard();

    @Test
    @DisplayName("정상 응답: 임계치 초과 + 키워드 없음 → withheld=false")
    void passesNormalResponse() {
        Stage2Output out = new Stage2Output(Sentiment.POSITIVE, new BigDecimal("0.820"),
                "정상적인 자기주식취득 공시입니다.");
        PromptGuard.GuardResult r = guard.sanitize(out, 0.6);
        assertThat(r.withheld()).isFalse();
        assertThat(r.forbiddenHit()).isFalse();
        assertThat(r.sanitized().summary()).isEqualTo("정상적인 자기주식취득 공시입니다.");
    }

    @Test
    @DisplayName("신뢰도 임계 미만 → withheld=true, sentiment/confidence 보존")
    void withholdsBelowThreshold() {
        Stage2Output out = new Stage2Output(Sentiment.NEGATIVE, new BigDecimal("0.450"),
                "정보가 부족합니다.");
        PromptGuard.GuardResult r = guard.sanitize(out, 0.6);
        assertThat(r.withheld()).isTrue();
        assertThat(r.withheldByThreshold()).isTrue();
        assertThat(r.forbiddenHit()).isFalse();
        assertThat(r.sanitized().sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(r.sanitized().confidence()).isEqualByComparingTo(new BigDecimal("0.450"));
    }

    @Test
    @DisplayName("자본시장법 금지 키워드 매칭 → withheld=true 강제 (신뢰도와 무관)")
    void forcesWithholdOnForbiddenKeyword() {
        Stage2Output out = new Stage2Output(Sentiment.POSITIVE, new BigDecimal("0.950"),
                "이 종목 매수를 추천합니다.");
        PromptGuard.GuardResult r = guard.sanitize(out, 0.6);
        assertThat(r.withheld()).isTrue();
        assertThat(r.forbiddenHit()).isTrue();
        assertThat(r.withheldByThreshold()).isFalse();
    }

    @Test
    @DisplayName("여러 금지 키워드 패턴 매칭")
    void matchesAllForbiddenPatterns() {
        String[] cases = {
                "꼭 사세요",
                "지금 매도하세요",
                "수익 보장",
                "확정 수익이 가능합니다",
                "손실 없이 매수 가능",
                "이 종목을 추천드립니다"
        };
        for (String summary : cases) {
            Stage2Output out = new Stage2Output(Sentiment.NEUTRAL, new BigDecimal("0.900"), summary);
            PromptGuard.GuardResult r = guard.sanitize(out, 0.6);
            assertThat(r.forbiddenHit())
                    .as("'%s' 는 금지 키워드 매칭되어야 함", summary)
                    .isTrue();
            assertThat(r.withheld()).isTrue();
        }
    }

    @Test
    @DisplayName("summary 240자 초과 → 마지막 마침표 부근에서 truncate")
    void capsSummaryAtSentenceBoundary() {
        String longSummary = ("이 회사는 자기주식 취득을 결정했습니다. "
                + "이는 통상 주주가치 부양 신호로 해석됩니다. "
                + "그러나 시장 상황에 따라 효과는 달라질 수 있습니다. "
                + "또한 회사의 재무 건전성도 함께 고려해야 합니다. "
                + "투자 판단은 신중히 하셔야 합니다. "
                + "잡담 추가로 240자를 명확히 넘기는 문장을 더합니다. "
                + "잡담 추가로 240자를 명확히 넘기는 문장을 더합니다.");

        Stage2Output out = new Stage2Output(Sentiment.POSITIVE, new BigDecimal("0.700"), longSummary);
        PromptGuard.GuardResult r = guard.sanitize(out, 0.6);

        assertThat(r.sanitized().summary().length()).isLessThanOrEqualTo(PromptGuard.SUMMARY_MAX_CHARS);
        // 마침표로 끝나야 함(앞쪽 240자 내 마지막 마침표 부근)
        assertThat(r.sanitized().summary()).endsWith(".");
    }

    @Test
    @DisplayName("240자 초과 + 마침표 없음 → 240자 절단 + … 부착")
    void capsSummaryWithEllipsisWhenNoSentenceBoundary() {
        // 240자가 모두 종결 부호 없음
        String summary = "가".repeat(300);
        Stage2Output out = new Stage2Output(Sentiment.NEUTRAL, new BigDecimal("0.800"), summary);
        PromptGuard.GuardResult r = guard.sanitize(out, 0.6);

        assertThat(r.sanitized().summary()).endsWith("…");
        assertThat(r.sanitized().summary().length()).isEqualTo(PromptGuard.SUMMARY_MAX_CHARS + 1);
    }
}
