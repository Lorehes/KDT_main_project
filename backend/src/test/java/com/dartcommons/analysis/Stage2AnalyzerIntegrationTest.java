package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.analysis.services.Stage2Analyzer;
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

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] Stage2Analyzer 통합 검증 — MockLlmClient + Testcontainers PostgreSQL로 골든 패스 + 멱등 + 신뢰도 임계.
 * [이유] Spec §7 G3: 신규 분석 골든 + 파싱 실패 폴백 + 신뢰도 임계 + 금지 키워드 + 백필 + REST = 5+ 시나리오.
 *       본 테스트는 wave 2 범위 — 골든/멱등/임계. 파싱 폴백·백필·REST는 wave 3+ 도입.
 *       MockLlmClient의 결정론 키워드 분기(악재시나리오/판단보류시나리오)로 케이스 유도.
 * [사이드 임팩트] @TestPropertySource로 LLM provider=mock 강제 — 실 Ollama 호출 차단.
 *               DisclosurePollingJob을 MockitoBean으로 차단 → 외부 DART 호출 없음.
 * [수정 시 고려사항] MockLlmClient의 키워드 분기를 변경할 경우 본 테스트의 fixture 동기 갱신.
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
class Stage2AnalyzerIntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob;

    @Autowired private Stage2Analyzer analyzer;
    @Autowired private AnalysisResultRepository resultRepo;
    @Autowired private DisclosureRepository disclosureRepo;

    @BeforeEach
    void setUp() {
        resultRepo.deleteAll();
        disclosureRepo.deleteAll();
    }

    @Test
    @DisplayName("골든 패스: 호재 시나리오 → POSITIVE + 임계 초과 + withheld=false 저장")
    void positiveGoldenPath() {
        Disclosure d = saveDisclosure("자기주식취득결과보고서 호재시나리오", "TREASURY_STOCK");

        Optional<AnalysisResult> result = analyzer.analyze(d.getId());

        assertThat(result).isPresent();
        AnalysisResult ar = result.get();
        assertThat(ar.getSentiment()).isEqualTo(Sentiment.POSITIVE);
        assertThat(ar.isWithheld()).isFalse();
        assertThat(ar.getStageReached()).isEqualTo((short) 2);
        assertThat(ar.getModelName()).isEqualTo("mock-model");
        assertThat(ar.getSummary()).contains("주주");
    }

    @Test
    @DisplayName("판단 보류 시나리오: 신뢰도 0.3 < 임계 0.6 → withheld=true 강제")
    void withholdsLowConfidence() {
        Disclosure d = saveDisclosure("판단보류시나리오 보고서", "OTHER");

        Optional<AnalysisResult> result = analyzer.analyze(d.getId());

        assertThat(result).isPresent();
        AnalysisResult ar = result.get();
        assertThat(ar.isWithheld()).isTrue();
        assertThat(ar.getConfidence().doubleValue()).isLessThan(0.6);
    }

    @Test
    @DisplayName("악재 시나리오: NEGATIVE + 임계 초과")
    void negativeScenario() {
        Disclosure d = saveDisclosure("주요사항보고서(감자결정) 악재시나리오", "CAPITAL_REDUCTION");

        Optional<AnalysisResult> result = analyzer.analyze(d.getId());

        assertThat(result).isPresent();
        AnalysisResult ar = result.get();
        assertThat(ar.getSentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(ar.isWithheld()).isFalse();
    }

    @Test
    @DisplayName("멱등: 같은 disclosureId 재호출 → 첫 결과 보존, 빈 Optional 반환")
    void idempotentSecondCall() {
        Disclosure d = saveDisclosure("자기주식취득결과보고서 호재시나리오", "TREASURY_STOCK");

        Optional<AnalysisResult> first = analyzer.analyze(d.getId());
        Optional<AnalysisResult> second = analyzer.analyze(d.getId());

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        assertThat(resultRepo.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("미존재 공시: 빈 Optional + 저장 없음")
    void missingDisclosureReturnsEmpty() {
        Optional<AnalysisResult> result = analyzer.analyze(99_999_999L);
        assertThat(result).isEmpty();
        assertThat(resultRepo.count()).isEqualTo(0);
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
}
