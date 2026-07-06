package com.dartcommons.analysis.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] analysis_results.stage_details JSONB의 다단계 상세를 감싸는 래퍼 record.
 *       Stage 2(key_points/요인) + Stage 5(financial_impact/risk_assessment) 병합 저장.
 * [이유] analysis-stage5-financial-industry Spec 카드 #6: Stage2Detail을 직접 저장하던 평면 구조에서
 *       래퍼로 전환해 하위 호환을 유지하면서 Stage 5 필드를 추가.
 *       하위 호환 전략: AnalysisQueryService.parseDetail()이 래퍼 파싱 실패 시 평면 Stage2Detail 폴백.
 *       별도 Flyway 마이그레이션 불필요 — JSONB 컬럼 재사용(db_schema §5 SSOT).
 * [사이드 임팩트] Stage2Analyzer: 기존 Stage2Detail 직렬화를 StageDetailEnvelope.ofStage2()로 교체.
 *               Stage5Analyzer: stage_details를 읽어 Stage5 필드 추가 후 재저장.
 *               AnalysisQueryService.parseDetail(): 래퍼 시도 → 실패 시 평면 Stage2Detail 폴백(기존 데이터 보호).
 * [수정 시 고려사항] Stage 6+ 추가 시 이 record에 필드 추가 → 저장/조회 양쪽 동기.
 *                  stage2/stage5 필드명 변경 금지(기존 DB 데이터와 JSON 키 불일치).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record StageDetailEnvelope(
        @JsonProperty("stage2") Stage2Detail stage2,
        @JsonProperty("stage5") Stage5Detail stage5
) {

    /** Stage 2만 있는 신규 저장용 (Stage5Analyzer 실행 전). */
    public static StageDetailEnvelope ofStage2(Stage2Detail stage2) {
        return new StageDetailEnvelope(stage2, null);
    }

    /** Stage 5 결과를 기존 래퍼에 병합. stage2는 그대로 보존. */
    public StageDetailEnvelope withStage5(Stage5Detail stage5) {
        return new StageDetailEnvelope(this.stage2, stage5);
    }

    /** Stage 2 상세 추출 — null-safe. 평면 폴백 경로에서도 사용. */
    public Stage2Detail getStage2() {
        return stage2;
    }

    /**
     * Stage 5 재무 분석 상세.
     * financial_impact: 재무 관점 영향(사실 기반 서술, 수치는 FE에서 스냅샷 원본으로 렌더).
     * riskAssessment: 재무/업황 리스크(자본시장법 L2 PromptGuard 적용 대상).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Stage5Detail(
            @JsonProperty("financial_impact")  String financialImpact,
            @JsonProperty("risk_assessment")   String riskAssessment,
            // ponytail: 업황 Spec 전까지 null 고정(Stage5PromptBuilder에서 null 주입) — 후속 Spec 시 Stage5PromptBuilder 섹션 추가
            @JsonProperty("industry_context")  String industryContext
    ) {}
}
