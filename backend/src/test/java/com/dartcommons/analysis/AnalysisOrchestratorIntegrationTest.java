package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.entities.AnalysisResult.Sentiment;
import com.dartcommons.analysis.repositories.AnalysisJobRepository;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.analysis.services.AnalysisBackfillService;
import com.dartcommons.analysis.services.AnalysisOrchestrator;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.shared.event.AnalysisCompletedEvent;
import com.dartcommons.shared.event.DisclosureCollectedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/*
 * [목적] wave 3 통합 검증 — Orchestrator(이벤트 트리거) + AnalysisBackfillService(91k 시뮬) + Controller 위임.
 * [이유] Spec §7 G3: 골든 + 백필 진행률 + REST = 다중 시나리오. wave 1/2(Mock 단위/통합)에 이어 비동기/배치 검증.
 *       Awaitility로 @Async 완료 대기. MockLlmClient로 LLM 실 호출 차단.
 * [사이드 임팩트] @TestPropertySource로 LLM provider=mock 강제, DisclosurePollingJob MockitoBean.
 *               @TransactionalEventListener(AFTER_COMMIT) 동작 검증을 위해 픽스처 INSERT 후 publishEvent 직접 호출.
 *               비동기라 결과 검증은 Awaitility.untilAsserted.
 * [수정 시 고려사항] flaky 위험 줄이려면 풀 사이즈 충분 + 시나리오 격리(@BeforeEach deleteAll).
 *                  Stage 3+ 진입 시 본 테스트는 그대로 — Orchestrator 코드 변경에 따라 보강.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, AnalysisOrchestratorIntegrationTest.TestConfig.class})
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.llm.provider=mock",
        "dartcommons.llm.base-url=http://localhost:11434",
        "dartcommons.llm.model=mock-model",
        "dartcommons.llm.timeout-ms=1000",
        "dartcommons.llm.max-retries=1",
        "dartcommons.llm.confidence-threshold=0.6",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password"
})
class AnalysisOrchestratorIntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob;

    @Autowired private AnalysisOrchestrator orchestrator;
    @Autowired private AnalysisBackfillService backfillService;
    @Autowired private AnalysisResultRepository resultRepo;
    @Autowired private AnalysisJobRepository jobRepo;
    @Autowired private DisclosureRepository disclosureRepo;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private CompletedCaptor completedCaptor;

    @BeforeEach
    void setUp() {
        resultRepo.deleteAll();
        jobRepo.deleteAll();
        disclosureRepo.deleteAll();
        completedCaptor.clear();
    }

    @Test
    @DisplayName("Orchestrator: DisclosureCollectedEvent → AFTER_COMMIT → 분석 + AnalysisCompletedEvent 발행")
    void orchestratorTriggersOnEventAndPublishesCompleted() {
        Disclosure d = persistAndPublish("자기주식취득결과보고서 호재시나리오", "TREASURY_STOCK");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(resultRepo.findByDisclosureId(d.getId())).isPresent();
            AnalysisResult ar = resultRepo.findByDisclosureId(d.getId()).get();
            assertThat(ar.getSentiment()).isEqualTo(Sentiment.POSITIVE);
            assertThat(ar.isWithheld()).isFalse();
            assertThat(completedCaptor.events()).hasSize(1);
            AnalysisCompletedEvent e = completedCaptor.events().get(0);
            assertThat(e.disclosureId()).isEqualTo(d.getId());
            assertThat(e.sentiment()).isEqualTo(Sentiment.POSITIVE);
        });
    }

    @Test
    @DisplayName("Orchestrator: 판단 보류 시나리오는 withheld=true로 저장 + Completed 이벤트의 withheld 필드 반영")
    void orchestratorPropagatesWithheld() {
        Disclosure d = persistAndPublish("판단보류시나리오 보고서", "OTHER");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(resultRepo.findByDisclosureId(d.getId())).isPresent();
            assertThat(resultRepo.findByDisclosureId(d.getId()).get().isWithheld()).isTrue();
            assertThat(completedCaptor.events()).hasSize(1);
            assertThat(completedCaptor.events().get(0).withheld()).isTrue();
        });
    }

    @Test
    @DisplayName("AnalysisBackfillService: 미분석 공시 3건을 chunkSize=2로 처리 → 전건 분석 완료 + jobStatus=SUCCEEDED")
    void backfillProcessesAllUnanalyzed() {
        Disclosure d1 = saveDisclosure("자기주식취득결과보고서 호재시나리오 A", "TREASURY_STOCK");
        Disclosure d2 = saveDisclosure("주요사항보고서(감자결정) 악재시나리오 B", "CAPITAL_REDUCTION");
        Disclosure d3 = saveDisclosure("판단보류시나리오 C", "OTHER");

        AnalysisJob job = backfillService.createJob(null, null, 2);
        backfillService.runAsync(job.getJobId());

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AnalysisJob refreshed = jobRepo.findByJobId(job.getJobId()).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AnalysisJob.Status.SUCCEEDED);
            assertThat(refreshed.getAnalyzed()).isEqualTo(3);
            assertThat(refreshed.getFailed()).isEqualTo(0);
            assertThat(refreshed.getTargeted()).isEqualTo(3);
            assertThat(refreshed.getChunksDone()).isEqualTo(refreshed.getChunksTotal());
            assertThat(resultRepo.existsByDisclosureId(d1.getId())).isTrue();
            assertThat(resultRepo.existsByDisclosureId(d2.getId())).isTrue();
            assertThat(resultRepo.existsByDisclosureId(d3.getId())).isTrue();
        });
    }

    @Test
    @DisplayName("AnalysisBackfillService: 이미 분석된 공시는 skip — targeted=0이면 즉시 SUCCEEDED")
    void backfillSkipsWhenAllAnalyzed() {
        Disclosure d = saveDisclosure("자기주식취득결과보고서 호재시나리오", "TREASURY_STOCK");
        // 사전 분석 결과 INSERT (Mock 결과)
        resultRepo.save(AnalysisResult.builder()
                .disclosureId(d.getId())
                .sentiment(Sentiment.POSITIVE)
                .confidence(new java.math.BigDecimal("0.800"))
                .withheld(false)
                .summary("이미 분석됨")
                .stageReached((short) 2)
                .build());

        AnalysisJob job = backfillService.createJob(null, null, 100);
        backfillService.runAsync(job.getJobId());

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            AnalysisJob refreshed = jobRepo.findByJobId(job.getJobId()).orElseThrow();
            assertThat(refreshed.getStatus()).isEqualTo(AnalysisJob.Status.SUCCEEDED);
            assertThat(refreshed.getTargeted()).isEqualTo(0);
            assertThat(refreshed.getAnalyzed()).isEqualTo(0);
        });
    }

    // --- fixture 헬퍼 ---

    /**
     * AFTER_COMMIT 이벤트 트리거에는 활성 트랜잭션 필수 — TransactionTemplate으로 명시 트랜잭션 안에서 INSERT + publish.
     * 트랜잭션 커밋 시점에 비로소 @TransactionalEventListener가 발화.
     */
    Disclosure persistAndPublish(String reportNm, String type) {
        return transactionTemplate.execute(status -> {
            Disclosure d = saveDisclosure(reportNm, type);
            eventPublisher.publishEvent(new DisclosureCollectedEvent(d.getId()));
            return d;
        });
    }

    private Disclosure saveDisclosure(String reportNm, String type) {
        return disclosureRepo.save(Disclosure.builder()
                .rceptNo(String.valueOf(System.nanoTime()).substring(0, 14))
                .corpCode("00000000")
                .corpName("테스트회사")
                .reportNm(reportNm)
                .rceptDt(LocalDate.of(2026, 6, 5))
                .disclosureType(type)
                .build());
    }

    @TestConfiguration
    static class TestConfig {
        @Bean CompletedCaptor completedCaptor() { return new CompletedCaptor(); }
    }

    @Component
    static class CompletedCaptor {
        private final List<AnalysisCompletedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void on(AnalysisCompletedEvent e) {
            events.add(e);
        }

        public List<AnalysisCompletedEvent> events() { return events; }
        public void clear() { events.clear(); }
    }
}
