package com.dartcommons.analysis.dto;

import java.math.BigDecimal;

/*
 * [목적] Stage 5 LLM 재무/업황 분석(최종) 구조화 출력 — record로 강제 파싱(환각 방지, CLAUDE.md §6-6).
 *       financialImpact·riskAssessment·industryContext·confidence 4필드.
 * [이유] analysis-stage5-financial-industry Spec R4. Stage4Output과 동일 패턴 — 스키마 파싱 실패 시 재시도.
 *       confidence는 파싱 가드용으로만 수신, 저장 안 함(Stage 4와 동일 정책).
 *       industryContext는 nullable — 업황 공공 API 연동 전까지 LLM이 생성하지 않도록 프롬프트 지시.
 * [사이드 임팩트] Stage5Analyzer가 파싱 결과에서 financialImpact·riskAssessment만 stage_details에 UPDATE.
 *               industryContext: 업황 후속 Spec 구현 시 Stage5PromptBuilder + 이 record 필드 채움.
 * [수정 시 고려사항] 필드 추가 시 LlmClient 구현체 3종(Ollama/OpenRouter/Mock) Stage5OutputRaw 동기 필요.
 */
public record Stage5Output(
        String financialImpact,
        String riskAssessment,
        String industryContext,   // nullable (업황 후속)
        BigDecimal confidence
) {}
