package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.dto.Stage2Detail;
import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.analysis.dto.StageDetailEnvelope;
import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.services.Stage2Analyzer;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.shared.enums.Tier;
import com.dartcommons.analysis.repositories.AnalysisJobRepository;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.infrastructure.llm.LlmClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] wave 1 산출물(V13 마이그레이션, AnalysisResult/Job 엔티티, MockLlmClient, AnalysisResponse 티어 차등)을
 *       Testcontainers 실 PostgreSQL로 회귀 검증.
 * [이유] Mock DB 금지(CLAUDE.md §6-6) — V13 CHECK 제약, UNIQUE, NUMERIC(4,3) 정밀도, ENUM 매핑까지 실 검증.
 *       wave 1 검증 게이트(Spec Tech Review §7): 마이그레이션 통과 + Mock 골든 패스 1건.
 * [사이드 임팩트] LLM provider=mock 으로 강제 → MockLlmClient 빈 활성. 운영 application.yml 의 ollama 차단.
 *               DisclosurePollingJob을 MockitoBean으로 차단해 실 DART 호출 방지.
 * [수정 시 고려사항] wave 2 도입 시 LLM 호출 시나리오 분기 추가 — 본 테스트는 wave 1 골격 검증에 한정.
 *                  Disclosure 픽스처는 V4 스키마 필수 필드만 채움.
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
class AnalysisWave1IntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob;

    @Autowired private AnalysisJobRepository jobRepo;
    @Autowired private AnalysisResultRepository resultRepo;
    @Autowired private DisclosureRepository disclosureRepo;
    @Autowired private LlmClient llmClient;
    @Autowired private Stage2Analyzer stage2Analyzer;
    @Autowired private ObjectMapper objectMapper;

    @Test
    @DisplayName("V13 마이그레이션 적용 + AnalysisJob 영속화 라운드트립")
    @Transactional
    void analysisJobPersists() {
        AnalysisJob job = AnalysisJob.create((short) 2, null, null, 100);
        jobRepo.save(job);

        Optional<AnalysisJob> found = jobRepo.findByJobId(job.getJobId());
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(AnalysisJob.Status.PENDING);
        assertThat(found.get().getStage()).isEqualTo((short) 2);
        assertThat(found.get().getChunkSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("AnalysisResult V5 매핑 + UNIQUE(disclosure_id) + 미분석 조회")
    @Transactional
    void analysisResultPersistsAndQueriesUnanalyzed() {
        // 공시 2건 적재 — 1건만 분석
        Disclosure d1 = saveDisclosure("20260604000001", "삼성전자", "사업보고서", "ANNUAL_REPORT");
        Disclosure d2 = saveDisclosure("20260604000002", "LG에너지솔루션", "유상증자결정", "RIGHTS_OFFERING");

        AnalysisResult r1 = AnalysisResult.builder()
                .disclosureId(d1.getId())
                .sentiment(Sentiment.NEUTRAL)
                .confidence(new BigDecimal("0.700"))
                .withheld(false)
                .summary("통상적인 정기 공시입니다.")
                .stageReached((short) 2)
                .modelName("mock-model")
                .build();
        resultRepo.save(r1);

        // 라운드트립
        assertThat(resultRepo.findByDisclosureId(d1.getId())).isPresent();
        assertThat(resultRepo.existsByDisclosureId(d1.getId())).isTrue();
        assertThat(resultRepo.existsByDisclosureId(d2.getId())).isFalse();

        // 미분석 목록 — d2만 조회되어야 함
        List<Long> unanalyzed = resultRepo.findUnanalyzedDisclosureIds(null, null, PageRequest.of(0, 10));
        assertThat(unanalyzed).containsExactly(d2.getId());
        assertThat(resultRepo.countUnanalyzedDisclosures(null, null)).isEqualTo(1);
    }

    @Test
    @DisplayName("MockLlmClient: provider=mock 환경에서 결정론적 분류 응답")
    void mockLlmClientClassifiesDeterministically() {
        Stage2Output negative = llmClient.classifyStage2("악재시나리오 감자");
        assertThat(negative.sentiment()).isEqualTo(Sentiment.NEGATIVE);
        assertThat(negative.confidence()).isEqualByComparingTo(new BigDecimal("0.850"));

        Stage2Output withheld = llmClient.classifyStage2("판단보류시나리오");
        assertThat(withheld.confidence()).isEqualByComparingTo(new BigDecimal("0.300"));
        // 임계치 0.6 미만 — withheld 처리는 호출 측 책임이지만 본 응답이 임계치 미만임을 확인
        assertThat(withheld.confidence().compareTo(new BigDecimal("0.6"))).isNegative();
    }

    @Test
    @DisplayName("AnalysisResponse 티어 차등 — FREE는 Pro+ 필드 null, disclaimer 항상 포함")
    void analysisResponseTierDifferentiation() {
        AnalysisResult ar = AnalysisResult.builder()
                .id(99L)
                .disclosureId(1L)
                .sentiment(Sentiment.POSITIVE)
                .confidence(new BigDecimal("0.820"))
                .withheld(false)
                .summary("주주 가치 긍정.")
                .stageReached((short) 4)
                .expectedReaction(AnalysisResult.ExpectedReaction.UP)
                .rationale("과거 동일 유형 평균 +3.2% 반응")
                .createdAt(OffsetDateTime.now())
                .build();

        AnalysisResponse freeResp = AnalysisResponse.from(ar, Tier.FREE);
        assertThat(freeResp.sentiment()).isEqualTo(Sentiment.POSITIVE);
        assertThat(freeResp.expectedReaction()).isNull();   // Free 제외
        assertThat(freeResp.rationale()).isNull();
        assertThat(freeResp.disclaimer()).isEqualTo(AnalysisResponse.DISCLAIMER);
        assertThat(freeResp.reportInaccuracyPath()).isEqualTo("/api/v1/analyses/99/feedback");

        AnalysisResponse proResp = AnalysisResponse.from(ar, Tier.PRO);
        assertThat(proResp.expectedReaction()).isEqualTo(AnalysisResult.ExpectedReaction.UP);
        assertThat(proResp.rationale()).contains("과거 동일 유형");
        assertThat(proResp.disclaimer()).isEqualTo(AnalysisResponse.DISCLAIMER);
    }

    @Test
    @DisplayName("Wave 2: analyze() → stage_details(JSONB) 왕복 — key_points/요인 직렬화·역직렬화 실검증")
    @Transactional
    void stage2DetailJsonbRoundTrip() throws Exception {
        // report_nm에 "감자" 포함 → Stage2PromptBuilder 프롬프트에 반영 → MockLlmClient 악재 시나리오(요인 생성)
        Disclosure d = saveDisclosure("20260702000010", "테스트기업", "감자결정", "OTHER");

        Optional<AnalysisResult> saved = stage2Analyzer.analyze(d.getId());
        assertThat(saved).isPresent();
        assertThat(saved.get().getSentiment()).isEqualTo(Sentiment.NEGATIVE);

        // 실 PostgreSQL JSONB 컬럼에서 다시 읽어 String+SqlTypes.JSON 매핑이 이중 인코딩 없이 왕복하는지 검증
        AnalysisResult reloaded = resultRepo.findByDisclosureId(d.getId()).orElseThrow();
        assertThat(reloaded.getStageDetails()).isNotNull();

        // StageDetailEnvelope 래퍼 포맷(카드 #6) — stage2.key_points/요인 확인
        StageDetailEnvelope envelope = objectMapper.readValue(reloaded.getStageDetails(), StageDetailEnvelope.class);
        Stage2Detail detail = envelope.getStage2();
        assertThat(detail).isNotNull();
        assertThat(detail.keyPoints()).isNotEmpty();
        assertThat(detail.negativeFactors()).isNotEmpty();
        assertThat(detail.positiveFactors()).isEmpty();  // 악재 시나리오는 호재 요인 없음
    }

    @Test
    @DisplayName("Wave 2: withheld면 stage_details 미저장 — 신뢰 못하는 분석의 요인 영속·노출 차단(§11.1)")
    @Transactional
    void stage2WithheldSuppressesDetail() {
        // 신뢰도 0.3 < 임계 0.6 → withheld=true. 판단보류 시나리오는 요인도 없지만, withheld 시 저장 억제 경로 검증.
        Disclosure d = saveDisclosure("20260702000011", "테스트기업", "판단보류시나리오 공시", "OTHER");

        Optional<AnalysisResult> saved = stage2Analyzer.analyze(d.getId());
        assertThat(saved).isPresent();
        assertThat(saved.get().isWithheld()).isTrue();
        assertThat(saved.get().getStageDetails()).isNull();
    }

    // --- 픽스처 헬퍼 ---

    private Disclosure saveDisclosure(String rceptNo, String corpName, String reportNm, String type) {
        return disclosureRepo.save(Disclosure.builder()
                .rceptNo(rceptNo)
                .corpCode("00000000")
                .corpName(corpName)
                .reportNm(reportNm)
                .rceptDt(LocalDate.of(2026, 6, 4))
                .disclosureType(type)
                .build());
    }
}
