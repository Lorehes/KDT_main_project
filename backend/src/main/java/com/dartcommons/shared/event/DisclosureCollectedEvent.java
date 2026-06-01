package com.dartcommons.shared.event;

/*
 * [목적] 공시 1건 적재 완료를 analysis 도메인에 알리는 도메인 이벤트.
 *       disclosureId 하나만 싣고, 수신측(AnalysisOrchestrator)이 자신의 리포지토리로 상세 조회한다.
 * [이유] disclosure→analysis 직접 import 금지(CLAUDE.md §3-2) — 이벤트로 결합도 해소.
 *       @TransactionalEventListener(AFTER_COMMIT)와 함께 써서 미완료 트랜잭션에 대한 분석 시작 방지.
 * [사이드 임팩트] 현재 리스너 없음(analysis 도메인 후속 구현). 이벤트가 발행돼도 소비자 없으면 무해히 무시됨.
 * [수정 시 고려사항] 페이로드 확장 시 disclosureId 외 필드는 최소화 — 수신측이 DB 조회로 보완.
 *                  멀티 리스너가 생기면 각 리스너에 @Async 추가 필요(폴링 스레드 블로킹 방지).
 */
public record DisclosureCollectedEvent(Long disclosureId) {
}
