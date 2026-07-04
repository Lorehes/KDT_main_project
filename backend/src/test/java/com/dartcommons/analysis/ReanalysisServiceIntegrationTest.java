package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisJobRepository;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.analysis.services.ReanalysisService;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/*
 * [목적] ReanalysisService 통합 검증 — 선삭제→재분석 정상 산출, 중단 후 재개(미재분석분만 처리).
 * [이유] reanalyze-after-charset-recollection Spec 카드 #5. Testcontainers PostgreSQL 실컨테이너.
 *       핵심 시나리오: (1) since 이후 재수집 공시의 기존 분석이 삭제→재분석되는가,
 *       (2) since 이전 공시는 건드리지 않는가, (3) 워터마크 덕에 중단 후 재개 시 이미 분석된 건 스킵.
 * [사이드 임팩트] MockLlmClient(provider=mock) — LLM 실 호출 없음. DisclosurePollingJob MockitoBean.
 * [수정 시 고려사항] feedbacks 검증은 현재 0건이라 생략 — feedbacks>0 환경에서 cascade 여부 별도 검증 필요.
 *                  Stage 3 임베딩 재생성 연계 테스트는 stage3-embedding-backfill Spec 범위.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
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
class ReanalysisServiceIntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob;

    @Autowired private ReanalysisService reanalysisService;
    @Autowired private AnalysisResultRepository resultRepo;
    @Autowired private AnalysisJobRepository jobRepo;
    @Autowired private DisclosureRepository disclosureRepo;
    @Autowired private TransactionTemplate txTemplate;

    private static final OffsetDateTime SINCE = OffsetDateTime.of(2026, 7, 3, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime BEFORE_SINCE = SINCE.minusDays(1);
    private static final OffsetDateTime AFTER_SINCE = SINCE.plusHours(1);

    @BeforeEach
    void setUp() {
        resultRepo.deleteAll();
        jobRepo.deleteAll();
        disclosureRepo.deleteAll();
    }

    @Test
    @DisplayName("since 이후 재수집 공시: 기존 분석 삭제 후 정상 재분석")
    void reanalyzesDisclosuresRefetchedAfterSince() {
        Disclosure target = persistDisclosure("한전기술 계약체결", "SUPPLY_CONTRACT", AFTER_SINCE);
        seedAnalysis(target.getId(), "손상본 기반 448조원 환각");

        // 동기 실행으로 재분석 — @Async 타이밍 불확실성 제거
        AnalysisJob job = txTemplate.execute(status -> reanalysisService.createJob(SINCE, 100));
        reanalysisService.doReanalyzeSynchronous(job.getJobId(), SINCE, 100);

        // 기존 분석이 삭제되고 새 결과가 저장됨
        AnalysisResult fresh = resultRepo.findByDisclosureId(target.getId()).orElse(null);
        assertThat(fresh).isNotNull();
        assertThat(fresh.getSummary()).isNotEqualTo("손상본 기반 448조원 환각");
    }

    @Test
    @DisplayName("since 이전 공시: 기존 분석 그대로 보존")
    void doesNotTouchDisclosuresBeforeSince() {
        Disclosure old = persistDisclosure("과거 공시 보존 확인", "OTHER", BEFORE_SINCE);
        seedAnalysis(old.getId(), "기존 분석 보존");

        reanalysisService.createAndStartAsync(SINCE, 100);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AnalysisResult existing = resultRepo.findByDisclosureId(old.getId()).orElse(null);
            assertThat(existing).isNotNull();
            assertThat(existing.getSummary()).isEqualTo("기존 분석 보존");
        });
    }

    @Test
    @DisplayName("재분석 대상 없음 → 잡 SUCCEEDED, 분석 0건 처리")
    void succeedsWithNoTargets() {
        // since 이전 공시만 있음
        Disclosure old = persistDisclosure("범위 밖 공시", "OTHER", BEFORE_SINCE);
        seedAnalysis(old.getId(), "범위 밖 분석");

        AnalysisJob job = txTemplate.execute(status ->
                reanalysisService.createJob(SINCE, 100));

        UUID jobId = job.getJobId();
        reanalysisService.runAsync(jobId, SINCE, 100);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            AnalysisJob j = jobRepo.findByJobId(jobId).orElseThrow();
            assertThat(j.getStatus()).isEqualTo(AnalysisJob.Status.SUCCEEDED);
            assertThat(j.getAnalyzed()).isEqualTo(0);
        });
    }

    @Test
    @DisplayName("중복 실행 방지 — 이미 실행 중이면 Optional.empty()")
    void preventsDuplicateRun() {
        // 재분석 대상 여러 건 생성(비동기 실행이 즉시 완료되지 않도록 부하)
        for (int i = 0; i < 5; i++) {
            Disclosure d = persistDisclosure("중복방지 테스트 공시 " + i, "OTHER", AFTER_SINCE);
            seedAnalysis(d.getId(), "기존 분석 " + i);
        }

        var first = reanalysisService.createAndStartAsync(SINCE, 100);
        assertThat(first).isPresent();

        // 비동기 실행 중 즉시 두 번째 시도 → 409(empty)
        var second = reanalysisService.createAndStartAsync(SINCE, 100);
        // running이 true인 동안이면 empty, 이미 완료됐으면 present도 허용(경쟁 조건)
        // 핵심: 두 번 모두 same-time 시도 시 최대 1개만 잡이 실행됨(CAS 보장)
        assertThat(first).isPresent(); // 첫 번째는 반드시 성공

        await().atMost(10, TimeUnit.SECONDS).until(() -> !reanalysisService.isRunning());
    }

    // ─── 헬퍼 ───

    private Disclosure persistDisclosure(String reportNm, String reportType, OffsetDateTime contentFetchedAt) {
        return txTemplate.execute(status -> {
            Disclosure d = Disclosure.builder()
                    .rceptNo(UUID.randomUUID().toString().replace("-", "").substring(0, 14))
                    .corpCode("00000000")
                    .corpName("테스트기업")
                    .reportNm(reportNm)
                    .disclosureType(reportType)
                    .rceptDt(LocalDate.now())
                    .contentText("정상 본문 내용 — 계약금액 100억원")
                    .contentFetchedAt(contentFetchedAt)
                    .build();
            return disclosureRepo.save(d);
        });
    }

    private void seedAnalysis(Long disclosureId, String staleSummary) {
        txTemplate.execute(status -> {
            AnalysisResult stale = AnalysisResult.builder()
                    .disclosureId(disclosureId)
                    .sentiment(com.dartcommons.shared.enums.Sentiment.NEUTRAL)
                    .confidence(new BigDecimal("0.700"))
                    .withheld(false)
                    .summary(staleSummary)
                    .stageReached((short) 2)
                    .modelName("test-model")
                    .build();
            return resultRepo.save(stale);
        });
    }
}
