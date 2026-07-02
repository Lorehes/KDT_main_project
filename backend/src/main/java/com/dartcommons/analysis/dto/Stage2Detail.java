package com.dartcommons.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/*
 * [목적] Stage 2 구조화 상세(이런 내용이에요 / 호재 요인 / 악재 요인)의 영속·전송 페이로드 —
 *       analysis_results.stage_details(JSONB)에 직렬화 저장, 조회 시 역직렬화되어 AnalysisResponse에 편입.
 * [이유] disclosure-detail-redesign Wave 2: 신규 필드를 정규 컬럼으로 추가하면 Flyway 마이그레이션이 필요하나,
 *       기존 stage_details JSONB 컬럼을 재활용하면 스키마 무변경으로 저장 가능(Tech Review 확정).
 *       snake_case(@JsonProperty)로 저장 — API 응답(AnalysisResponse) 및 FE(disclosures.ts)와 키 일관성 유지.
 * [사이드 임팩트] Stage2Analyzer가 저장 시 직렬화, AnalysisQueryService가 조회 시 역직렬화 — 두 지점이 이 스키마에 의존.
 *               구버전 분석(stage_details=null)은 조회 시 detail=null → 신규 카드 미노출(FE 폴백). 하위 호환.
 * [수정 시 고려사항] 필드 추가 시 저장/조회 양쪽 + AnalysisResponse + FE 타입 동기 필요.
 *                  Stage 4~5 상세를 같은 컬럼에 병합 저장하려면 본 record에 Stage4 필드 추가 또는 래퍼 도입.
 */
public record Stage2Detail(
        @JsonProperty("key_points") List<String> keyPoints,
        @JsonProperty("positive_factors") List<String> positiveFactors,
        @JsonProperty("negative_factors") List<String> negativeFactors
) {
    /** 세 리스트가 모두 비어 있으면 저장 가치 없음 — Stage2Analyzer가 stage_details=null 유지 판단에 사용. */
    public boolean isEmpty() {
        return (keyPoints == null || keyPoints.isEmpty())
                && (positiveFactors == null || positiveFactors.isEmpty())
                && (negativeFactors == null || negativeFactors.isEmpty());
    }
}
