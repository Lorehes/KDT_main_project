package com.dartcommons.analysis.controllers;

import com.dartcommons.analysis.dto.FeedbackRequest;
import com.dartcommons.analysis.services.FeedbackService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] POST /api/v1/analyses/{id}/feedback — 분석 피드백(유용함/부정확함) 저장.
 * [이유] 피드백은 분석 결과의 하위 액션 — /analyses/{id}/feedback 경로로 명시(api_spec §2.4).
 *       동일 사용자가 재투표 시 UPDATE(uq_feedbacks_user_analysis) — FeedbackService.upsert()가 처리.
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] GET /analyses/{id}/feedback(피드백 조회)는 후속 Spec에서 추가.
 *                  INACCURATE 임계치 초과 시 분석 비공개 처리 로직은 FeedbackService 확장으로 추가.
 */
@RestController
@RequestMapping("/api/v1/analyses")
@RequiredArgsConstructor
public class AnalysisController {

    private final FeedbackService feedbackService;

    @PostMapping("/{id}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void submitFeedback(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @Valid @RequestBody FeedbackRequest request
    ) {
        feedbackService.upsert(userId, id, request);
    }
}
