package com.dartcommons.shared.enums;

/*
 * [목적] analysis_results.stage_reached + analysis_jobs.stage 의 매직 넘버를 의미 있는 상수로 관리.
 * [이유] analysis-stage4-llm-final 리뷰 M-2 이월: Stage 5 구현 시점에 상수화 약속.
 *       Stage 번호를 직접 써도 동작하지만, Stage 5 추가 시 `5`를 어디는 넣고 어디는 빼먹는 오탐 방지.
 * [사이드 임팩트] JPQL 쿼리(AnalysisResultRepository stageReached = 2 등)도 이 상수로 교체 가능하나,
 *               JPQL에서 static import가 복잡하므로 Java 코드 레벨의 (short) 캐스팅 대체에 한정.
 * [수정 시 고려사항] Stage 6 이상 추가 시 이 클래스에 상수만 추가 — 영향 파일 없음.
 *                  short 타입: AnalysisResult.stageReached 컬럼 타입과 정합 (SMALLINT).
 */
public final class AnalysisStage {

    private AnalysisStage() {}

    /** Stage 1: 룰 기반 분류 (Disclosure 수집 시 자동). */
    public static final short RULE       = 1;
    /** Stage 2: LLM 1차 분류 (sentiment·confidence·summary). */
    public static final short LLM_CLASSIFY = 2;
    /** Stage 3: 임베딩 + RAG 유사 공시 검색 (Chroma). */
    public static final short RAG        = 3;
    /** Stage 4: LLM 2차 최종 판단 (expected_reaction·rationale). */
    public static final short LLM_FINAL  = 4;
    /** Stage 5: 재무/업황 분석 (financial_context — Premium). */
    public static final short FINANCIAL  = 5;
}
