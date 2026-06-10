package com.dartcommons.analysis.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/*
 * [목적] 유사 공시 항목 DTO — AnalysisResponse.similarDisclosures 타입 시그니처 확정(R6).
 * [이유] List<Object>는 타입 안전성이 없어 FE 계약을 코드에서 표현할 수 없음.
 *       Stage 3(RAG 검색) 구현 전에도 필드 구조를 record로 확정해 서비스·테스트 코드가 타입을 참조할 수 있도록.
 * [사이드 임팩트] Stage 3 구현 전에는 AnalysisResponse.similarDisclosures가 항상 null — FE는 undefined 처리.
 *               Stage 3 구현 시 AnalysisQueryService·RAGService에서 이 타입으로 결과를 조립.
 * [수정 시 고려사항] priceReaction5dPct 필드가 없으면 FE PriceReactionChart가 렌더 불가.
 *                  Stage 3 구현 시 필드 추가는 하위 호환 — record는 불변이므로 새 record 정의 필요.
 */
public record SimilarDisclosureItem(
        @JsonProperty("rcept_no")              String rceptNo,
        @JsonProperty("corp_name")             String corpName,
        @JsonProperty("rcept_dt")              String rceptDt,
        @JsonProperty("price_reaction_5d_pct") double priceReaction5dPct
) {}
