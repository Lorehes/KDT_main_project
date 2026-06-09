package com.dartcommons.analysis.repositories;

import com.dartcommons.analysis.entities.FeedbackEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/*
 * [목적] feedbacks 테이블 CRUD — 분석 피드백 저장·재투표(upsert 패턴).
 * [이유] uq_feedbacks_user_analysis UNIQUE 제약 기반 upsert: 기존 레코드 findByUserIdAndAnalysisId → update.
 *       INSERT 시 UniqueViolation 대신 application 계층 분기로 UPDATE 경로를 명시적으로 처리.
 * [사이드 임팩트] idx_feedbacks_analysis_verdict 인덱스(analysis_id, verdict) — 분석별 피드백 통계 쿼리에 활용.
 * [수정 시 고려사항] 대량 통계 집계는 @Query + GROUP BY 추가. INACCURATE 임계치 초과 감지 쿼리 추가 가능.
 */
public interface FeedbackRepository extends JpaRepository<FeedbackEntity, Long> {

    Optional<FeedbackEntity> findByUserIdAndAnalysisId(Long userId, Long analysisId);
}
