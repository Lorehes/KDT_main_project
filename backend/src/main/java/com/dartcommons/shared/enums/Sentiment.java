package com.dartcommons.shared.enums;

/*
 * [목적] 공시 분석 호재/중립/악재 분류 enum — 도메인 간 공유 단일 진실 소스.
 * [이유] 기존 analysis.entities.AnalysisResult 중첩 enum으로 정의되어 notification·infra·shared/event가
 *       analysis 도메인에 역방향 의존(CLAUDE.md §3-2 위반). shared/enums 이관으로 정합 복구.
 * [사이드 임팩트] AnalysisResult.sentiment 필드(@Enumerated(EnumType.STRING)) 타입 교체.
 *               DB VARCHAR 값(POSITIVE/NEUTRAL/NEGATIVE) 불변 — 마이그레이션 불필요.
 *               LLM 파싱(Sentiment.valueOf)은 클래스 경로 무관하게 동작 유지.
 * [수정 시 고려사항] 값 추가·삭제 시 DB 데이터·LLM 프롬프트·알림 메시지 빌더 동시 갱신 필요.
 */
public enum Sentiment {
    POSITIVE,
    NEUTRAL,
    NEGATIVE
}
