package com.dartcommons.disclosure.services;

import com.dartcommons.shared.event.DisclosureCollectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * [목적] 신규 공시 적재 직후(AFTER_COMMIT) DART 본문 fetch를 비동기로 트리거.
 *       DisclosureCollectedEvent를 수신해 DisclosureContentService.fetchAndSave()를 호출한다.
 * [이유] AFTER_COMMIT 보장: 트랜잭션 커밋 전에 fetch가 시작되면 disclosure가 DB에 없어 fetchAndSave()가 not-found 처리됨.
 *       @Async("contentFetchExecutor"): 폴링 스레드를 블로킹하지 않고 별도 풀에서 실행(SLO 보호).
 *       AnalysisOrchestrator와 동일 패턴(@TransactionalEventListener + @Async) — 팀 내 패턴 일관성.
 * [사이드 임팩트] 예외는 catch 후 warn 로그만 — 이벤트 발행 사이드에 예외를 전파하지 않음(폴링 중단 방지).
 *               동일 공시에 대해 이벤트가 중복 발행되면 fetchAndSave()의 멱등 로직(contentFetchedAt 체크)이 방어.
 *               contentFetchExecutor 큐가 가득 차면 TaskRejectedException — warn 로그로 포착됨.
 * [수정 시 고려사항] Stage 3 RAG 활성화 시 content_text 저장 후 임베딩 트리거 이벤트가 추가될 수 있음.
 *                  그 경우 fetchAndSave() 성공 후 ApplicationEventPublisher.publishEvent() 추가 검토.
 */
@Component
public class DisclosureContentFetchListener {

    private static final Logger log = LoggerFactory.getLogger(DisclosureContentFetchListener.class);

    private final DisclosureContentService disclosureContentService;

    public DisclosureContentFetchListener(DisclosureContentService disclosureContentService) {
        this.disclosureContentService = disclosureContentService;
    }

    @Async("contentFetchExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDisclosureCollected(DisclosureCollectedEvent event) {
        try {
            disclosureContentService.fetchAndSave(event.disclosureId());
        } catch (Exception e) {
            // 폴링 루프에 예외가 전파되지 않도록 silent fail
            log.warn("DisclosureContentFetchListener: fetch failed for disclosureId={} reason={}",
                    event.disclosureId(), e.getMessage());
        }
    }
}
