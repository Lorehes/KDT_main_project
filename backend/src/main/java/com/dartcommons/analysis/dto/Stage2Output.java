package com.dartcommons.analysis.dto;

import com.dartcommons.analysis.entities.AnalysisResult.Sentiment;

import java.math.BigDecimal;

/*
 * [목적] Stage 2 LLM 1차 분석의 구조화된 출력 — record로 강제 파싱(환각 방지, CLAUDE.md §6-6).
 *       LlmClient가 이 타입으로 반환하므로 파싱 실패는 호출 측에서 재시도/폴백.
 * [이유] 통합기획서 §6.1 Stage 2 명세: 분류(POSITIVE/NEUTRAL/NEGATIVE) + 신뢰도(0~1) + 3줄 요약.
 *       LangChain4j AiServices가 본 record로 JSON 응답을 자동 파싱(wave 2).
 * [사이드 임팩트] 필드 추가 시 LLM 프롬프트 스키마 동기 갱신 필요.
 *               confidence는 BigDecimal — double 사용 시 NUMERIC(4,3) 정밀도 손실.
 * [수정 시 고려사항] Stage 3~5는 별도 record(Stage4Output 등)로 분리 — 본 Spec=2 한정.
 *                  summary 글자수 cap(240자)은 호출 측 PromptGuard에서 강제.
 */
public record Stage2Output(
        Sentiment sentiment,
        BigDecimal confidence,
        String summary
) {
}
