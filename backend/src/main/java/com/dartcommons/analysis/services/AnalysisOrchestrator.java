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
 * [목적] disclosure 도메인의 DisclosureCollectedEvent를 구독해 Stage 2 → Stage 3 분석을 순차 비동기 트리거.
 *       Stage 2 완료 → Stage 3 임베딩 upsert → AnalysisCompletedEvent 발행 — notification(M3)이 후속 구독.
 *       feature_structure §1.2 + §2 시퀀스의 analysis 영역 진입점.
 * [이유] CLAUDE.md §3-2: disclosure → analysis 직접 의존 금지 → 이벤트 경유.
 *       AFTER_COMMIT: 수집 트랜잭션 커밋 후 분석 시작 → 부분 실패 격리.
 *       @Async("analysisExecutor"): 폴링 SLO(30초) 보호 풀에서 비동기 실행 (analysisBackfillExecutor와 별개).
 * [사이드 임팩트] Stage 3 upsert 실패(Ollama/Chroma 미기동)는 warn 로그 후 publishCompleted 계속 — Stage 3 실패가 알림 차단하지 않음.
 *               MockEmbeddingClient + MockChromaClient 기본 활성 → upsert가 인메모리 Mock에 저장됨 (운영 영향 없음).
 *               Stage2Analyzer 결과 Optional.empty()는 silent skip — Stage 3도 실행 안 됨, 이벤트 발행 없음.
 * [수정 시 고려사항] Stage 6 추가 시 Stage5Analyzer 다음에 동일 격리 패턴으로 체인.
 *                  대량 폴링 시 풀(core 2 / max 4) 큐 가득 → CallerRunsPolicy로 호출 스레드 직접 실행 (이벤트 발행 스레드 잠시 지연).
 */
@Component
public class AnalysisOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AnalysisOrchestrator.class);

    private final Stage2Analyzer stage2Analyzer;
    private final Stage3RagService stage3RagService;
    private final Stage4Analyzer stage4Analyzer;
    private final Stage5Analyzer stage5Analyzer;
    private final ApplicationEventPublisher eventPublisher;

    public AnalysisOrchestrator(Stage2Analyzer stage2Analyzer,
                                Stage3RagService stage3RagService,
                                Stage4Analyzer stage4Analyzer,
                                Stage5Analyzer stage5Analyzer,
                                ApplicationEventPublisher eventPublisher) {
        this.stage2Analyzer = stage2Analyzer;
        this.stage3RagService = stage3RagService;
        this.stage4Analyzer = stage4Analyzer;
        this.stage5Analyzer = stage5Analyzer;
        this.eventPublisher = eventPublisher;
    }

    @Async("analysisExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onDisclosureCollected(DisclosureCollectedEvent event) {
        Long disclosureId = event.disclosureId();
        try {
            stage2Analyzer.analyze(disclosureId).ifPresent(ar -> {
                try { stage3RagService.upsert(disclosureId); }
                catch (Exception e3) { log.warn("Stage3 upsert 실패(무시) disclosureId={} err={}", disclosureId, e3.getMessage()); }
                try { stage4Analyzer.analyze(disclosureId); }
                catch (Exception e4) { log.warn("Stage4 분석 실패(무시) disclosureId={} err={}", disclosureId, e4.getMessage()); }
                // Stage 5: 재무 스냅샷 없으면 내부 skip(결정 C) — Premium 전용, 실패해도 알림 발행 계속
                try { stage5Analyzer.analyze(disclosureId); }
                catch (Exception e5) { log.warn("Stage5 분석 실패(무시) disclosureId={} err={}", disclosureId, e5.getMessage()); }
                publishCompleted(ar);
            });
        } catch (Exception e) {
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
