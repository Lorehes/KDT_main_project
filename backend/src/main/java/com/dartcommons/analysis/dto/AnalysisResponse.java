package com.dartcommons.analysis.dto;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.entities.AnalysisResult.ExpectedReaction;
import com.dartcommons.analysis.entities.AnalysisResult.Sentiment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/*
 * [목적] GET /api/v1/disclosures/{id}/analysis 응답 DTO — api_spec §2.4 명세 정합.
 *       티어 차등은 null 필드 = JSON 키 제외(@JsonInclude(NON_NULL))로 처리. "노출 후 마스킹 금지" 원칙 준수.
 * [이유] CLAUDE.md §6-6 / api_spec §2.4 불변 규칙: confidence 항상 포함, is_withheld=true면 "판단 보류",
 *       모든 응답에 disclaimer + reportInaccuracyPath 동반(자본시장법 + LLM 환각 가드).
 *       Free 사용자에게 Pro+ 필드가 응답에 포함되어선 안 됨 — 티어 미달 필드는 null로 두면 직렬화 제외.
 * [사이드 임팩트] Jackson 기본 설정에서 null 필드가 직렬화되면 본 DTO 의도 깨짐.
 *               필드 추가 시 api_spec.md §2.4 동기 갱신 필수.
 * [수정 시 고려사항] disclaimer/reportInaccuracyPath는 정적 상수가 아닌 필드 — 응답 직렬화 단계에서 강제 주입.
 *                  similarDisclosures/financialContext는 본 Spec wave 1 범위 밖 — Stage 3~5 후속.
 *                  티어 판정은 controller 계층(현재 wave 1은 permitAll, M2 user/JWT 합류 후 @PreAuthorize).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "analysisId", "disclosureId",
        "sentiment", "confidence", "isWithheld", "summary", "stageReached",
        "expectedReaction", "rationale", "similarDisclosures",
        "financialContext",
        "disclaimer", "reportInaccuracyPath", "createdAt"
})
public record AnalysisResponse(
        Long analysisId,
        Long disclosureId,
        Sentiment sentiment,
        BigDecimal confidence,
        boolean isWithheld,
        String summary,
        short stageReached,

        // Pro+ (Stage 3~4) — Free 응답에서는 null로 두어 직렬화 제외
        ExpectedReaction expectedReaction,
        String rationale,
        List<Object> similarDisclosures,

        // Premium (Stage 5) — Free/Pro 응답에서는 null
        Object financialContext,

        // 항상 포함(법적 보호)
        String disclaimer,
        String reportInaccuracyPath,
        OffsetDateTime createdAt
) {

    /**
     * 자본시장법 + LLM 환각 가드 면책 문구 — api_spec §2.4 예시 문장.
     * 응답 직렬화 시 항상 동일 문장. 변경 시 법무 검수 필요.
     */
    public static final String DISCLAIMER =
            "본 분석은 정보 제공용이며 투자 자문/권유가 아닙니다. AI 분석은 부정확할 수 있으며 투자 책임은 이용자에게 있습니다.";

    public enum Tier { FREE, PRO, PREMIUM }

    /**
     * 엔티티 + 티어로 티어 차등 응답 생성.
     * - FREE  : Stage 1~2 필드만, expected_reaction/rationale/similar/financial 제외
     * - PRO   : Stage 1~4 필드(financial 제외)
     * - PREMIUM : 전체
     *
     * is_withheld=true는 모든 티어에서 동일 전달 — 화면이 "판단 보류" 처리.
     */
    public static AnalysisResponse from(AnalysisResult ar, Tier tier) {
        String reportPath = "/api/v1/analyses/" + ar.getId() + "/feedback";

        boolean proPlus = tier == Tier.PRO || tier == Tier.PREMIUM;
        boolean premium = tier == Tier.PREMIUM;

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

                premium ? null : null, // financial_context — Stage 5 후속

                DISCLAIMER,
                reportPath,
                ar.getCreatedAt()
        );
    }
}
