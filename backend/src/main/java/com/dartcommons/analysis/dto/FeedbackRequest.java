package com.dartcommons.analysis.dto;

import com.dartcommons.analysis.entities.FeedbackEntity.Verdict;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/*
 * [목적] POST /api/v1/analyses/{id}/feedback 요청 DTO — 유용함/부정확함 판정 + 선택적 사유.
 * [이유] verdict는 nullable이면 DB CHECK 제약 위반 — @NotNull 검증. reason은 선택(null 허용).
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] reason 길이 캡은 앱 계층(FeedbackService) 권장값 2000자.
 *                  verdict 변경 시 FeedbackEntity.Verdict enum + V9 CHECK 제약 동시 갱신.
 */
public record FeedbackRequest(
        @NotNull Verdict verdict,
        @Size(max = 2000) String reason
) {}
