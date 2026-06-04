package com.dartcommons.analysis.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.shared.event.AnalysisCompletedEvent;
import com.dartcommons.shared.event.DisclosureCollectedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * [목적] disclosure 도메인의 DisclosureCollectedEvent를 구독해 Stage 2 분석을 비동기 트리거.
 *       분석 완료 후 AnalysisCompletedEvent 발행 — notification(M3)이 후속 구독.
 *       feature_structure §1.2 + §2 시퀀스의 analysis 영역 진입점.
 * [이유] CLAUDE.md §3-2: disclosure → analysis 직접 의존 금지 → 이벤트 경유.
 *       AFTER_COMMIT: 수집 트랜잭션 커밋 후 분석 시작 → 부분 실패 격리.
 *       @Async("analysisExecutor"): 폴링 SLO(30초) 보호 풀에서 비동기 실행 (analysisBackfillExecutor와 별개).
 * [사이드 임팩트] DisclosureCollectionService/Backfill이 이미 DisclosureCollectedEvent 발행 중 → 본 리스너 등록만으로 폴링 신규 공시가 자동 분석.
 *               Stage2Analyzer 결과 Optional.empty()는 silent skip(이미 분석됨/공시 없음/LLM 실패) — 이벤트 발행 안 함.
 *               성공 시 AnalysisCompletedEvent 발행 — notification 미구독 환경에서는 무해 무시.
 * [수정 시 고려사항] 대량 폴링 시 풀(core 2 / max 4) 큐 가득 → CallerRunsPolicy로 호출 스레드 직접 실행 (이벤트 발행 스레드 잠시 지연).
 *                  Stage 3+ 후속 도입 시 본 Orchestrator가 추가 단계 호출 — 별도 Stage3Analyzer/Stage4Analyzer 위임.
 */
@Component
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

    private final Stage2Analyzer stage2Analyzer;
    private final ApplicationEventPublisher eventPublisher;

    public AnalysisOrchestrator(Stage2Analyzer stage2Analyzer,
                                ApplicationEventPublisher eventPublisher) {
        this.stage2Analyzer = stage2Analyzer;
        this.eventPublisher = eventPublisher;
    }

    @Async("analysisExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDisclosureCollected(DisclosureCollectedEvent event) {
        Long disclosureId = event.disclosureId();
        try {
            stage2Analyzer.analyze(disclosureId).ifPresent(this::publishCompleted);
        } catch (Exception e) {
            // Stage2Analyzer 자체가 silent fail이지만, 예상치 못한 RuntimeException 차단 — 풀 워커 종료 방지.
            log.warn("AnalysisOrchestrator: 예상치 못한 예외 disclosureId={} err={}",
                    disclosureId, e.getMessage(), e);
        }
    }

    private void publishCompleted(AnalysisResult ar) {
        eventPublisher.publishEvent(new AnalysisCompletedEvent(
                ar.getId(),
                ar.getDisclosureId(),
                ar.getSentiment(),
                ar.getConfidence(),
                ar.isWithheld()
        ));
        log.debug("AnalysisCompletedEvent 발행: analysisId={} disclosureId={}",
                ar.getId(), ar.getDisclosureId());
    }
}
