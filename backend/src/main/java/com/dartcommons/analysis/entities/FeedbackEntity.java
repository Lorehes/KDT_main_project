package com.dartcommons.analysis.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] feedbacks 테이블(V9) JPA 엔티티 — 분석 결과에 대한 사용자 피드백(유용함/부정확함).
 * [이유] 정확도 KPI(§8.3 알림 정확도 90%) 검증 경로 + 도메인 특화 모델 학습 데이터 원천.
 *       uq_feedbacks_user_analysis UNIQUE 제약으로 동일 (user, analysis) 재투표는 application 계층에서 UPDATE 처리.
 * [사이드 임팩트] FeedbackService.upsert() 호출 시 existsByUserIdAndAnalysisId 분기로 INSERT/UPDATE 결정.
 * [수정 시 고려사항] INACCURATE 신고 임계치 초과 시 분석 비공개 처리는 후속 Spec(§11.1 리스크2).
 *                  reason은 최대 2000자 권장 — 앱 계층 캡 적용.
 */
@Entity
@Table(name = "feedbacks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class FeedbackEntity {

    public enum Verdict { USEFUL, INACCURATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "analysis_id", nullable = false)
    private Long analysisId;

    @Enumerated(EnumType.STRING)
    @Column(name = "verdict", nullable = false, length = 15)
    private Verdict verdict;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    private void prePersist() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }

    /** 재투표 시 verdict·reason 갱신 (uq_feedbacks_user_analysis 기준 UPDATE 경로). reason은 최대 2000자(V16 컬럼 제한). */
    public void update(Verdict verdict, String reason) {
        this.verdict = verdict;
        this.reason  = reason != null && reason.length() > 2000 ? reason.substring(0, 2000) : reason;
    }
}
