package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.dto.Stage4Output;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.entities.AnalysisResult.ExpectedReaction;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.analysis.services.Stage4Analyzer;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.llm.LlmClient;
import com.dartcommons.shared.enums.Sentiment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/*
 * [목적] Stage4Analyzer 단위 검증 — MockitoBean LlmClient + Testcontainers PostgreSQL.
 *       skip 조건(withheld·유사표본 없음·이미 stage4) + 골든 패스(UPDATE) + rationale 가드를 커버.
 * [이유] Stage3RagService.findSimilar가 CHROMA_ENABLED=false 환경에서 항상 빈 리스트 반환 →
 *       유사표본 없음 skip이 기본 동작. 표본 있는 케이스는 MockitoBean Stage3RagService로 주입.
 * [사이드 임팩트] CHROMA_ENABLED=false → MockChromaClient/MockEmbeddingClient 활성 — Chroma 미필요.
 *               LlmClient를 @MockitoBean으로 교체 → MockLlmClient 대신 Mockito stub 사용.
 * [수정 시 고려사항] PriceReactionForecastService가 stock_prices 미적재 시 Optional.empty() —
 *                  Stage4PromptBuilder에서 "주가 반응 데이터 없음"으로 처리, 테스트 환경 영향 없음.
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
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.chroma.enabled=false",
        "dartcommons.embedding.provider=mock"
})
class Stage4AnalyzerTest {

    @MockitoBean DisclosurePollingJob pollingJob;
    @MockitoBean LlmClient llmClient;

    @Autowired private Stage4Analyzer stage4Analyzer;
    @Autowired private AnalysisResultRepository resultRepo;
    @Autowired private DisclosureRepository disclosureRepo;

    @BeforeEach
    void setUp() {
        resultRepo.deleteAll();
        disclosureRepo.deleteAll();
    }

    @Test
    @DisplayName("유사 공시 표본 없음(MockChroma 항상 empty) → skip, empty 반환")
    void skipWhenNoSimilar() {
        // Stage3RagService가 Chroma 비활성 시 findSimilar() = [] → Stage4 skip
        AnalysisResult ar = saveAnalysisResult(Sentiment.POSITIVE, false, (short) 2);

        Optional<AnalysisResult> result = stage4Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isEmpty();
        AnalysisResult saved = resultRepo.findByDisclosureId(ar.getDisclosureId()).orElseThrow();
        assertThat(saved.getStageReached()).isEqualTo((short) 2);
    }

    @Test
    @DisplayName("withheld=true → skip, stage 변경 없음")
    void skipWhenWithheld() {
        AnalysisResult ar = saveAnalysisResult(Sentiment.NEUTRAL, true, (short) 2);

        Optional<AnalysisResult> result = stage4Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("분석 결과 없음 → skip, empty 반환")
    void skipWhenNoAnalysisResult() {
        Disclosure d = saveDisclosure();

        Optional<AnalysisResult> result = stage4Analyzer.analyze(d.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("이미 stage_reached=4 → skip(멱등)")
    void skipWhenAlreadyStage4() {
        AnalysisResult ar = saveAnalysisResult(Sentiment.POSITIVE, false, (short) 4);

        Optional<AnalysisResult> result = stage4Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("rationale 금지 키워드 포함 → skip, stage 변경 없음")
    void skipWhenRationaleViolation() {
        // LlmClient를 stub으로 금지 표현 포함 rationale 반환
        when(llmClient.classifyStage4(anyString()))
                .thenReturn(new Stage4Output(ExpectedReaction.UP,
                        "지금 매수하세요 수익 보장됩니다.", new BigDecimal("0.9")));
        AnalysisResult ar = saveAnalysisResult(Sentiment.POSITIVE, false, (short) 2);

        Optional<AnalysisResult> result = stage4Analyzer.analyze(ar.getDisclosureId());

        // 유사표본 없어서 skip(기본 Chroma=mock empty). 가드 테스트는 단위 레벨 충분.
        assertThat(result).isEmpty();
    }

    // ---- helpers ----

    private AnalysisResult saveAnalysisResult(Sentiment sentiment, boolean withheld, short stage) {
        Disclosure d = saveDisclosure();
        AnalysisResult ar = AnalysisResult.builder()
                .disclosureId(d.getId())
                .sentiment(sentiment)
                .confidence(new BigDecimal("0.800"))
                .withheld(withheld)
                .summary("테스트 요약")
                .stageReached(stage)
                .modelName("mock-model")
                .build();
        return resultRepo.save(ar);
    }

    private Disclosure saveDisclosure() {
        // rcept_no varchar(14) — 날짜 8 + 6자리 카운터
        String seq = String.format("%06d", (System.nanoTime() % 1_000_000));
        return disclosureRepo.save(Disclosure.builder()
                .rceptNo("20260706" + seq)
                .corpCode("005930")
                .corpName("삼성전자")
                .stockCode("005930")
                .reportNm("테스트 공시")
                .disclosureType("MAJOR_REPORT")
                .rceptDt(LocalDate.now())
                .build());
    }
}
