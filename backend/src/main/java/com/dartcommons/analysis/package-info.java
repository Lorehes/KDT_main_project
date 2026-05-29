/**
 * LLM 분석 도메인 (Stage 2~5, LangChain4j).
 *
 * <p>Stage 2 분류+요약 → Stage 3 임베딩/Chroma 유사검색+KRX 5일 반응 → Stage 4 최종판단 →
 * Stage 5 재무/업황(Premium). 각 Stage LLM 응답은 record 스키마로 파싱 후 저장(환각 방지),
 * confidence 필수·낮으면 is_withheld(판단 보류). {@code analysis_results} 저장.
 *
 * <p>표준 하위 패키지는 기능 구현 시 생성(빈 폴더 금지).
 * 의존 방향: shared → 도메인(역방향 금지), 도메인 간 통신은 이벤트 경유(CLAUDE.md 3-2, 6-6).
 */
package com.dartcommons.analysis;
