package com.dartcommons.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] 유사 공시 항목 DTO v2 — Stage 3 RAG 검색 결과 직렬화 타입.
 *       AnalysisResponse.similarDisclosures 요소 타입 + FE SimilarDisclosureCard 계약.
 * [이유] v1에서 priceReaction5dPct 제거 — KRX 주가 API 미구현(analysis-stage3-rag-chroma Spec A2 Tech Review).
 *       disclosureId 추가 — FE에서 공시 상세 링크(/disclosures/{id}) 직접 참조 가능.
 *       similarityScore 추가 — 유사도 수치를 FE에 노출해 "얼마나 비슷한가" 표시 가능.
 *       corpCode, disclosureType 추가 — 이중 쿼리 파티셔닝(Stage3RagService) + FE 필터링 지원.
 * [사이드 임팩트] v1 record(rceptNo/corpName/rceptDt/priceReaction5dPct)는 이 파일 교체로 완전 대체.
 *               FE disclosures.ts의 SimilarDisclosureItem 타입도 동기 갱신 필요(corpCode, disclosureType, similarityScore 추가).
 *               priceReaction5dPct 제거로 FE PriceReactionChart가 있다면 제거/비활성화 필요.
 * [수정 시 고려사항] Stage 5 구현 시 financialImpactSummary 필드 추가 검토.
 *                  KRX API 구현 후 priceReaction5dPct 재추가 시 별도 v3 record + FE 동기 갱신.
 */
public record SimilarDisclosureItem(
        @JsonProperty("disclosure_id")    Long disclosureId,
        @JsonProperty("rcept_no")         String rceptNo,
        @JsonProperty("corp_name")        String corpName,
        @JsonProperty("corp_code")        String corpCode,
        @JsonProperty("disclosure_type")  String disclosureType,
        @JsonProperty("rcept_dt")         String rceptDt,
        @JsonProperty("similarity_score") double similarityScore
) {}
