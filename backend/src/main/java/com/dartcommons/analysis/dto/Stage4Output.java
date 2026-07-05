package com.dartcommons.analysis.dto;

import com.dartcommons.analysis.entities.AnalysisResult.ExpectedReaction;

import java.math.BigDecimal;

/*
 * [목적] Stage 4 LLM 2차 분석(최종 판단) 구조화 출력 — record로 강제 파싱(환각 방지, CLAUDE.md §6-6).
 *       expected_reaction(UP/FLAT/DOWN)·rationale·confidence 3필드만 보유.
 * [이유] analysis-stage4-llm-final Spec 결정 2: confidence는 Stage2 값을 보존(Stage4 재평가 저장 안 함).
 *       Stage4Output.confidence는 "LLM이 이 판단을 얼마나 확신하는가"만 나타내며, 파싱 가드용으로만 사용.
 *       Stage2Output과 분리된 record — 출력 필드 구조가 달라 공유 시 파서 복잡도↑.
 * [사이드 임팩트] Stage4Analyzer가 파싱 결과에서 expectedReaction·rationale만 AnalysisResult에 UPDATE.
 *               confidence는 Stage2 값 유지(단일 신뢰도 소스 정책).
 * [수정 시 고려사항] Stage 5 도입 시 별도 Stage5Output record 분리 — 본 record=4 한정.
 *                  필드 추가 시 LlmClient 구현체 3종(Ollama/OpenRouter/Mock)의 Stage4OutputRaw 파서 동기 갱신 필요.
 */
public record Stage4Output(
        ExpectedReaction expectedReaction,
        String rationale,
        BigDecimal confidence
) {
}
