package com.dartcommons.analysis.dto;

import com.dartcommons.shared.enums.Sentiment;

import java.math.BigDecimal;
import java.util.List;

/*
 * [목적] Stage 2 LLM 1차 분석의 구조화된 출력 — record로 강제 파싱(환각 방지, CLAUDE.md §6-6).
 *       LlmClient가 이 타입으로 반환하므로 파싱 실패는 호출 측에서 재시도/폴백.
 * [이유] 통합기획서 §6.1 Stage 2 명세: 분류(POSITIVE/NEUTRAL/NEGATIVE) + 신뢰도(0~1) + 3줄 요약.
 *       disclosure-detail-redesign Wave 2: 공시 상세 목업의 Free 티어 카드(이런 내용이에요/호재·악재 요인)를
 *       위해 keyPoints·positiveFactors·negativeFactors 3개 리스트를 Stage 2 동일 호출에서 함께 생성.
 *       이 필드들은 stage_details JSONB로 저장(Flyway 불필요) — [[disclosure-detail-redesign]] Tech Review.
 * [사이드 임팩트] 필드 추가 시 LLM 프롬프트 스키마(Stage2PromptBuilder) + 각 LlmClient Raw 파서 동기 갱신 필요.
 *               confidence는 BigDecimal — double 사용 시 NUMERIC(4,3) 정밀도 손실.
 *               3-arg 호환 생성자 유지 — 기존 호출부(Mock 폴백/PromptGuard 재구성/테스트)는 빈 리스트로 무수정 동작.
 * [수정 시 고려사항] Stage 3~5는 별도 record(Stage4Output 등)로 분리 — 본 Spec=2 한정.
 *                  summary 글자수 cap(240자)은 호출 측 PromptGuard에서 강제. 리스트 항목의 자본시장법 가드도 PromptGuard 책임.
 *                  keyPoints/positiveFactors/negativeFactors는 null 대신 빈 리스트를 불변식으로 — 파서가 normalize.
 */
public record Stage2Output(
        Sentiment sentiment,
        BigDecimal confidence,
        String summary,
        List<String> keyPoints,
        List<String> positiveFactors,
        List<String> negativeFactors
) {
    /**
     * 3-arg 호환 생성자 — keyPoints/factors 미생성 경로(Mock 기본·PromptGuard 폴백·구버전 응답)에서
     * 빈 리스트로 채워 기존 호출부를 무수정 유지. Wave 2 신규 필드는 opt-in.
     */
    public Stage2Output(Sentiment sentiment, BigDecimal confidence, String summary) {
        this(sentiment, confidence, summary, List.of(), List.of(), List.of());
    }
}
