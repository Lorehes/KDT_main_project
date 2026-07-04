package com.dartcommons.infrastructure.llm;

import com.dartcommons.analysis.dto.Stage2Output;

/*
 * [목적] LLM provider 추상화 — Stage 2 분류 호출의 표준 인터페이스.
 *       MVP는 Ollama Local 구현, 실서비스는 Cloud 어댑터 교체로 전환 가능(통합기획서 §6.3).
 * [이유] LangChain4j 직접 의존을 analysis 도메인에서 격리(CLAUDE.md §3-2). 테스트는 Mock 주입.
 *       provider별 차이(모델명/타임아웃/에러 분류)를 구현체에 캡슐화.
 * [사이드 임팩트] 인터페이스 변경 시 모든 구현체(Ollama, Cloud, Mock) 동기 수정 필요.
 *               예외는 LlmException(또는 후속) — 호출 측은 unchecked로 처리.
 * [수정 시 고려사항] Stage 3~5는 별도 메서드(classifyStage4 등) 또는 제네릭 분리 — 본 IF=2 한정.
 *                  토큰 카운팅은 구현체가 응답 메타에서 추출해 별도 record로 반환 가능(wave 2).
 */
public interface LlmClient {

    /**
     * Stage 2 LLM 1차 분류 호출.
     *
     * @param prompt 완성된 프롬프트(시스템+사용자 메시지 병합 텍스트)
     * @return 파싱된 record. 파싱/통신 실패 시 RuntimeException throw.
     */
    Stage2Output classifyStage2(String prompt);
}
