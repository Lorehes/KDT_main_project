package com.dartcommons.analysis.dto;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.entities.AnalysisResult.ExpectedReaction;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.shared.enums.Tier;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/*
 * [목적] GET /api/v1/disclosures/{id}/analysis 응답 DTO — api_spec §2.4 명세 정합.
 *       티어 차등은 null 필드 = JSON 키 제외(@JsonInclude(NON_NULL))로 처리. "노출 후 마스킹 금지" 원칙 준수.
 * [이유] CLAUDE.md §6-6 / api_spec §2.4 불변 규칙: confidence 항상 포함, is_withheld=true면 "판단 보류",
 *       모든 응답에 disclaimer + reportInaccuracyPath 동반(자본시장법 + LLM 환각 가드).
 *       @JsonProperty snake_case: FE DisclosureAnalysis 타입(analysis_id, disclosure_id 등)과 1:1 대응.
 *       Free 사용자에게 Pro+ 필드가 응답에 포함되어선 안 됨 — 티어 미달 필드는 null로 두면 직렬화 제외.
 * [사이드 임팩트] Jackson 기본 설정에서 null 필드가 직렬화되면 본 DTO 의도 깨짐.
 *               필드 추가 시 api_spec.md §2.4 + FE disclosures.ts 동기 갱신 필수.
 * [수정 시 고려사항] disclaimer/reportInaccuracyPath는 정적 상수가 아닌 필드 — 응답 직렬화 단계에서 강제 주입.
 *                  similarDisclosures/financialContext는 본 Spec wave 1 범위 밖 — Stage 3~5 후속.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "analysis_id", "disclosure_id",
        "sentiment", "confidence", "is_withheld", "summary", "stage_reached",
        "expected_reaction", "rationale", "similar_disclosures",
        "financial_context",
        "disclaimer", "report_inaccuracy_path", "created_at"
})
public record AnalysisResponse(
        @JsonProperty("analysis_id")   Long analysisId,
        @JsonProperty("disclosure_id") Long disclosureId,
        Sentiment sentiment,
        BigDecimal confidence,
        @JsonProperty("is_withheld")   boolean isWithheld,
        String summary,
        @JsonProperty("stage_reached") short stageReached,

        // Pro+ (Stage 3~4) — Free 응답에서는 null로 두어 직렬화 제외
        @JsonProperty("expected_reaction") ExpectedReaction expectedReaction,
        String rationale,
        @JsonProperty("similar_disclosures") List<SimilarDisclosureItem> similarDisclosures,

        // Premium (Stage 5) — Free/Pro 응답에서는 null
        @JsonProperty("financial_context") Object financialContext,

        // 항상 포함(법적 보호)
        String disclaimer,
        @JsonProperty("report_inaccuracy_path") String reportInaccuracyPath,
        @JsonProperty("created_at") OffsetDateTime createdAt
) {

    /**
     * 자본시장법 + LLM 환각 가드 면책 문구 — api_spec §2.4 예시 문장.
     * 응답 직렬화 시 항상 동일 문장. 변경 시 법무 검수 필요.
     */
    public static final String DISCLAIMER =
            "본 분석은 정보 제공용이며 투자 자문/권유가 아닙니다. AI 분석은 부정확할 수 있으며 투자 책임은 이용자에게 있습니다.";

    /*
     * [목적] 엔티티 + 티어로 티어 차등 응답 생성 — FREE/PRO/PREMIUM 필드 화이트리스트 적용.
     * [이유] @JsonInclude(NON_NULL) 전략 — null 필드는 JSON에서 제외되어 FE가 미존재 필드를 하위 티어 표시로 처리.
     *       disclaimer/reportInaccuracyPath는 항상 포함 — 자본시장법 §11.1 면책 의무(CLAUDE.md §6-6, §7).
     *       is_withheld는 모든 티어에서 그대로 전달 — 화면이 "판단 보류" 처리.
     *       shared.enums.Tier를 직접 사용해 AnalysisQueryService의 switch 변환 보일러플레이트를 제거(R2).
     * [사이드 임팩트] financial_context는 Stage 5 미구현이므로 모든 티어에서 항상 null.
     *               Stage 5 구현 시 PREMIUM 분기를 추가해야 하며 AnalysisResponseTest PREMIUM 케이스도 함께 갱신 필요.
     * [수정 시 고려사항] tier 필드 추가 시 AnalysisResponseTest.allTiers_alwaysIncludeDisclaimerAndReportPath() 도 갱신.
     *                  proPlus 변수 범위는 PRO+PREMIUM — PREMIUM 전용 필드 추가 시 별도 premium 변수 복원.
     */
    public static AnalysisResponse from(AnalysisResult ar, Tier tier) {
        String reportPath = "/api/v1/analyses/" + ar.getId() + "/feedback";

        boolean proPlus = tier == Tier.PRO || tier == Tier.PREMIUM;

        return new AnalysisResponse(
                ar.getId(),
                ar.getDisclosureId(),
                ar.getSentiment(),
                ar.getConfidence(),
                ar.isWithheld(),
                ar.getSummary(),
                ar.getStageReached(),

                proPlus ? ar.getExpectedReaction() : null,
                proPlus ? ar.getRationale() : null,
                null, // similar_disclosures — Stage 3 후속 Spec에서 채움

                null, // TODO Stage-5: financial_context — Stage 5 구현 시 premium ? ar.getFinancialContext() : null 로 교체

                DISCLAIMER,
                reportPath,
                ar.getCreatedAt()
        );
    }
}
