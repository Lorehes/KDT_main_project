package com.dartcommons.user.dto;

/*
 * [목적] POST /api/v1/portfolios/import 응답 DTO — 등록·중복·미지원·한도 초과 종목코드 분류 결과.
 * [이유] FE는 각 리스트 길이로 토스트 메시지를 구성하므로 코드 목록 전체 반환이 필요.
 *       skipped_duplicate/unsupported/limit은 snake_case → @JsonProperty 명시(전역 설정 없음).
 * [사이드 임팩트] added 리스트는 실제 DB에 저장된 코드만 포함 — FE에서 invalidateQueries 트리거 시 즉시 반영됨.
 * [수정 시 고려사항] 카테고리 추가 시 FE(ImportPortfoliosResult 인터페이스 + toast 분기)도 동기화 필요.
 *                  added가 비어있고 나머지도 모두 비어있는 경우는 정상(50개 전부 중복일 때) — 별도 에러 없음.
 */
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record BulkImportResult(
        List<String> added,
        @JsonProperty("skipped_duplicate")   List<String> skippedDuplicate,
        @JsonProperty("skipped_unsupported") List<String> skippedUnsupported,
        @JsonProperty("skipped_limit")       List<String> skippedLimit
) {}
