package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.dto.Stage2Detail;
import com.dartcommons.analysis.dto.Stage5Output;
import com.dartcommons.analysis.dto.StageDetailEnvelope;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.analysis.services.Stage5Analyzer;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.llm.LlmClient;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.stocks.entities.FinancialSnapshot;
import com.dartcommons.stocks.repositories.FinancialSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/*
 * [목적] Stage5Analyzer 검증 — skip 게이트 + 골든 패스(stage_details 병합) + mergeStage5 하위호환.
 *       특히 리뷰 High 버그(평면 Stage2Detail이 래퍼 파싱 시 소리없이 유실) 회귀 테스트 포함.
 * [이유] analysis-stage5-financial-industry Spec R12. Stage4AnalyzerTest 패턴 답습.
 *       Testcontainers PostgreSQL(Mock DB 금지) + MockitoBean LlmClient.
 * [사이드 임팩트] financial_snapshots V31 마이그레이션이 테스트 컨테이너에 적용됨.
 * [수정 시 고려사항] MockLlmClient 대신 MockitoBean stub — 시나리오별 명시 응답 제어.
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
class Stage5AnalyzerTest {

    @MockitoBean DisclosurePollingJob pollingJob;
    @MockitoBean LlmClient llmClient;

    @Autowired private Stage5Analyzer stage5Analyzer;
    @Autowired private AnalysisResultRepository resultRepo;
    @Autowired private DisclosureRepository disclosureRepo;
    @Autowired private FinancialSnapshotRepository snapshotRepo;
    @Autowired private ObjectMapper objectMapper;

    private static final String CORP = "00126380";

    @BeforeEach
    void setUp() {
        resultRepo.deleteAll();
        snapshotRepo.deleteAll();
        disclosureRepo.deleteAll();
    }

    @Test
    @DisplayName("재무 스냅샷 없음 → skip(결정 C), stage 유지")
    void skipWhenNoSnapshot() {
        AnalysisResult ar = saveAnalysisResult((short) 4, false, null);

        Optional<AnalysisResult> result = stage5Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isEmpty();
        assertThat(resultRepo.findByDisclosureId(ar.getDisclosureId()).orElseThrow().getStageReached())
                .isEqualTo((short) 4);
    }

    @Test
    @DisplayName("stage_reached=2 (Stage 4 미완) → skip")
    void skipWhenStage4NotDone() {
        saveSnapshot();
        AnalysisResult ar = saveAnalysisResult((short) 2, false, null);

        assertThat(stage5Analyzer.analyze(ar.getDisclosureId())).isEmpty();
    }

    @Test
    @DisplayName("withheld=true → skip")
    void skipWhenWithheld() {
        saveSnapshot();
        AnalysisResult ar = saveAnalysisResult((short) 4, true, null);

        assertThat(stage5Analyzer.analyze(ar.getDisclosureId())).isEmpty();
    }

    @Test
    @DisplayName("이미 stage_reached=5 → skip(멱등)")
    void skipWhenAlreadyStage5() {
        saveSnapshot();
        AnalysisResult ar = saveAnalysisResult((short) 5, false, null);

        assertThat(stage5Analyzer.analyze(ar.getDisclosureId())).isEmpty();
    }

    @Test
    @DisplayName("골든 패스: 스냅샷+Stage4 완료 → stage_details 병합 + stage_reached=5")
    void goldenPath() throws Exception {
        saveSnapshot();
        when(llmClient.classifyStage5(anyString())).thenReturn(new Stage5Output(
                "매출 성장세가 이어지고 있습니다. 참고용 정보입니다.",
                "뚜렷한 재무 리스크는 확인되지 않습니다. 참고용 정보입니다.",
                null, new BigDecimal("0.8")));
        AnalysisResult ar = saveAnalysisResult((short) 4, false, null);

        Optional<AnalysisResult> result = stage5Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isPresent();
        assertThat(result.get().getStageReached()).isEqualTo((short) 5);
        StageDetailEnvelope env = objectMapper.readValue(result.get().getStageDetails(), StageDetailEnvelope.class);
        assertThat(env.stage5()).isNotNull();
        assertThat(env.stage5().financialImpact()).contains("매출");
        assertThat(env.stage5().riskAssessment()).contains("리스크");
    }

    @Test
    @DisplayName("회귀(리뷰 High): 평면 Stage2Detail 기존 데이터 → 병합 후 stage2 보존")
    void mergePreservesLegacyFlatStage2Detail() throws Exception {
        saveSnapshot();
        when(llmClient.classifyStage5(anyString())).thenReturn(new Stage5Output(
                "재무 영향 서술. 참고용 정보입니다.", "리스크 서술. 참고용 정보입니다.", null, new BigDecimal("0.8")));

        // Stage5 이전 저장 포맷: 평면 Stage2Detail JSON (래퍼 없음)
        String flatDetail = objectMapper.writeValueAsString(
                new Stage2Detail(List.of("핵심 내용 1"), List.of("호재 요인"), List.of()));
        AnalysisResult ar = saveAnalysisResult((short) 4, false, flatDetail);

        Optional<AnalysisResult> result = stage5Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isPresent();
        StageDetailEnvelope env = objectMapper.readValue(result.get().getStageDetails(), StageDetailEnvelope.class);
        // 핵심 검증: 기존 stage2 데이터가 유실되지 않고 래퍼로 승격
        assertThat(env.getStage2()).isNotNull();
        assertThat(env.getStage2().keyPoints()).containsExactly("핵심 내용 1");
        assertThat(env.getStage2().positiveFactors()).containsExactly("호재 요인");
        assertThat(env.stage5()).isNotNull();
    }

    @Test
    @DisplayName("래퍼 포맷 기존 데이터 → stage5만 추가, stage2 보존")
    void mergePreservesEnvelopeStage2() throws Exception {
        saveSnapshot();
        when(llmClient.classifyStage5(anyString())).thenReturn(new Stage5Output(
                "재무 영향. 참고용 정보입니다.", "리스크. 참고용 정보입니다.", null, new BigDecimal("0.8")));

        String envelopeDetail = objectMapper.writeValueAsString(StageDetailEnvelope.ofStage2(
                new Stage2Detail(List.of("래퍼 저장 항목"), List.of(), List.of())));
        AnalysisResult ar = saveAnalysisResult((short) 4, false, envelopeDetail);

        Optional<AnalysisResult> result = stage5Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isPresent();
        StageDetailEnvelope env = objectMapper.readValue(result.get().getStageDetails(), StageDetailEnvelope.class);
        assertThat(env.getStage2().keyPoints()).containsExactly("래퍼 저장 항목");
        assertThat(env.stage5()).isNotNull();
    }

    @Test
    @DisplayName("financialImpact 금지 키워드 → skip, stage 유지(자본시장법 가드)")
    void skipWhenGuardViolation() {
        saveSnapshot();
        when(llmClient.classifyStage5(anyString())).thenReturn(new Stage5Output(
                "지금 매수하세요.", "수익 보장됩니다.", null, new BigDecimal("0.9")));
        AnalysisResult ar = saveAnalysisResult((short) 4, false, null);

        Optional<AnalysisResult> result = stage5Analyzer.analyze(ar.getDisclosureId());

        assertThat(result).isEmpty();
        assertThat(resultRepo.findByDisclosureId(ar.getDisclosureId()).orElseThrow().getStageReached())
                .isEqualTo((short) 4);
    }

    // ---- helpers ----

    private void saveSnapshot() {
        snapshotRepo.save(FinancialSnapshot.builder()
                .corpCode(CORP).bsnsYear("2024").reprtCode("11011").fsDiv("CFS")
                .revenue(new BigDecimal("300000000000000"))
                .opProfit(new BigDecimal("32000000000000"))
                .netIncome(new BigDecimal("30000000000000"))
                .totalAssets(new BigDecimal("514000000000000"))
                .totalLiab(new BigDecimal("112000000000000"))
                .totalEquity(new BigDecimal("402000000000000"))
                .build());
    }

    private AnalysisResult saveAnalysisResult(short stage, boolean withheld, String stageDetails) {
        Disclosure d = saveDisclosure();
        return resultRepo.save(AnalysisResult.builder()
                .disclosureId(d.getId())
                .sentiment(Sentiment.POSITIVE)
                .confidence(new BigDecimal("0.800"))
                .withheld(withheld)
                .summary("테스트 요약")
                .stageDetails(stageDetails)
                .stageReached(stage)
                .modelName("mock-model")
                .build());
    }

    private Disclosure saveDisclosure() {
        String seq = String.format("%06d", (System.nanoTime() % 1_000_000));
        return disclosureRepo.save(Disclosure.builder()
                .rceptNo("20260706" + seq)
                .corpCode(CORP)
                .corpName("삼성전자")
                .stockCode("005930")
                .reportNm("테스트 공시")
                .disclosureType("MAJOR_REPORT")
                .rceptDt(LocalDate.now())
                .build());
    }
}
