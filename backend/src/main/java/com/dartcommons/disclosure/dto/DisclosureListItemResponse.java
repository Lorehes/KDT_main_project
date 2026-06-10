package com.dartcommons.disclosure.dto;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.shared.enums.Sentiment;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.Optional;

/*
 * [목적] GET /api/v1/disclosures 목록 + /disclosures/{id} 상세 응답 DTO — FE Disclosure 타입과 1:1 대응.
 * [이유] 공시 목록/상세에 분석 요약(sentiment·confidence·is_withheld·summary)을 포함해 FE가 카드 뷰를 렌더할 수 있도록.
 *       분석 미완료 공시는 sentiment 등 필드를 null로 두고 직렬화에서 제외(@JsonInclude(NON_NULL)).
 * [사이드 임팩트] corp_name·report_nm은 DART 원본 그대로 — LLM 변형 금지(CLAUDE.md §4).
 * [수정 시 고려사항] attachment_url은 현재 Stage 1 범위 밖(Disclosure.attachmentUrl = null) — 후속 Spec에서 채움.
 *                  confidence는 BigDecimal로 저장되나 JSON은 숫자로 직렬화됨.
 *                  패키지 위치: services/ → dto/ (CLAUDE.md §3-2 도메인 모듈 표준).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DisclosureListItemResponse(
        Long id,
        @JsonProperty("rcept_no")      String rceptNo,
        @JsonProperty("corp_name")     String corpName,
        @JsonProperty("stock_code")    String stockCode,
        @JsonProperty("report_nm")     String reportNm,
        @JsonProperty("rcept_dt")      String rceptDt,
        @JsonProperty("attachment_url") String attachmentUrl,
        // 분析 결과 (미완료 시 null → 직렬화 제외)
        Sentiment sentiment,
        BigDecimal confidence,
        @JsonProperty("is_withheld")   Boolean isWithheld,
        String summary
) {

    public static DisclosureListItemResponse from(Disclosure d, AnalysisResult ar) {
        Optional<AnalysisResult> opt = Optional.ofNullable(ar);
        return new DisclosureListItemResponse(
                d.getId(),
                d.getRceptNo(),
                d.getCorpName(),
                d.getStockCode(),
                d.getReportNm(),
                d.getRceptDt().toString(),
                d.getAttachmentUrl(),
                opt.map(AnalysisResult::getSentiment).orElse(null),
                opt.map(AnalysisResult::getConfidence).orElse(null),
                opt.map(AnalysisResult::isWithheld).orElse(null),
                opt.map(AnalysisResult::getSummary).orElse(null)
        );
    }
}
