package com.dartcommons.analysis;

import com.dartcommons.analysis.services.Stage2PromptBuilder;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.infrastructure.llm.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] Stage2PromptBuilder 본문 투입(stage2-body-in-prompt) 단위 검증 — 본문 섹션 포함/절삭/null 폴백.
 * [이유] 본문이 실제로 프롬프트에 들어가는지(num_ctx 상향과 함께 Spec 핵심), 상한 절삭·본문 없음 폴백 회귀 방지.
 *       DB 불필요 — Disclosure 픽스처 + LlmProperties 직접 구성.
 */
class Stage2PromptBuilderTest {

    private LlmProperties props(int bodyMax) {
        return new LlmProperties("ollama", "http://localhost:11434", "", "qwen3:4b", 60000, 2, 0.6, bodyMax);
    }

    private Disclosure disclosure(String contentText) {
        return Disclosure.builder()
                .rceptNo("20260701000001")
                .corpCode("00126380")
                .corpName("삼성전자")
                .stockCode("005930")
                .reportNm("전환사채권발행결정")
                .rceptDt(LocalDate.of(2026, 7, 1))
                .disclosureType("OTHER")
                .contentText(contentText)
                .build();
    }

    @Test
    @DisplayName("본문 있음 — 프롬프트에 본문 발췌 섹션 + 본문 근거 지시 포함")
    void build_withBody_includesExcerpt() {
        Stage2PromptBuilder builder = new Stage2PromptBuilder(props(6000));
        String prompt = builder.build(disclosure("1,000억원 규모 전환사채 발행을 결정하였습니다. 전환가액은 현재가 대비 할인 적용."));

        assertThat(prompt).contains("공시 본문(발췌");
        assertThat(prompt).contains("1,000억원 규모 전환사채 발행");
        assertThat(prompt).contains("발췌에 없는 사실은 추측하지 마세요");
        // 메타·스키마도 여전히 포함
        assertThat(prompt).contains("삼성전자").contains("전환사채권발행결정").contains("\"key_points\"");
    }

    @Test
    @DisplayName("본문 상한 초과 — stage2BodyMaxChars 글자로 절삭되어 삽입")
    void build_bodyExceedsCap_truncated() {
        int cap = 100;
        String longBody = "가".repeat(500);  // 500자 > cap 100
        Stage2PromptBuilder builder = new Stage2PromptBuilder(props(cap));
        String prompt = builder.build(disclosure(longBody));

        // 절삭된 본문(정확히 cap자)만 포함 — 500자 전체가 아니라 100자 블록
        assertThat(prompt).contains("가".repeat(cap));
        assertThat(prompt).doesNotContain("가".repeat(cap + 1));
    }

    @Test
    @DisplayName("본문 없음(null) — 본문 섹션 없이 메타 전용 폴백")
    void build_noBody_metadataOnlyFallback() {
        Stage2PromptBuilder builder = new Stage2PromptBuilder(props(6000));
        String prompt = builder.build(disclosure(null));

        assertThat(prompt).doesNotContain("공시 본문(발췌");
        assertThat(prompt).contains("본문이 제공되지 않았습니다");
        assertThat(prompt).contains("삼성전자").contains("\"positive_factors\"");
    }

    @Test
    @DisplayName("stage2BodyMaxChars=0 — 본문 있어도 미투입(메타 전용)")
    void build_capZero_bodyNotInjected() {
        Stage2PromptBuilder builder = new Stage2PromptBuilder(props(0));
        String prompt = builder.build(disclosure("본문 내용 있음"));

        assertThat(prompt).doesNotContain("공시 본문(발췌");
        assertThat(prompt).doesNotContain("본문 내용 있음");
        assertThat(prompt).contains("본문이 제공되지 않았습니다");
    }
}
