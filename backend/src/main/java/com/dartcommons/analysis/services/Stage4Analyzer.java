package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.PriceReactionForecast;
import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.analysis.dto.Stage4Output;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.infrastructure.llm.LlmClient;
import com.dartcommons.infrastructure.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/*
 * [목적] Stage 4 LLM 2차 분석(최종 판단) 단일 진입점 — Stage 3 유사 공시 + 주가 반응을 종합해
 *       expected_reaction(UP/FLAT/DOWN)·rationale을 기존 AnalysisResult에 UPDATE.
 * [이유] analysis-stage4-llm-final Spec: Stage 2 결과 + Stage 3 근거를 LLM에 재투입해 최종 판단.
 *       Spec 결정 (C): 유사 공시 표본 0이면 skip(Stage 2 종결) — LLM 예산 가드 + 근거 없는 판단 방지.
 *       Spec 결정 2: confidence는 Stage 2 값 보존 — Stage 4는 expected_reaction·rationale·stage_reached만 UPDATE.
 *       withheld 건 skip — "판단 보류"인데 방향 단정하는 모순 차단(통합기획서 §11.1).
 * [사이드 임팩트] Stage 3 upsert가 선행돼야 findSimilar 결과가 존재 — AnalysisOrchestrator가 순서 보장.
 *               LLM 호출 실패·파싱 실패는 AnalysisOrchestrator가 warn 후 무시(Stage 2 결과·알림 발행 차단 금지).
 *               PromptGuard.isRationaleViolation: rationale 투자 권유 표현 발견 시 skip(Optional.empty) — stage_reached 2 유지.
 *               재시도는 LlmClient 구현체의 @Retryable(최대 3회)에 단일 위임 — Analyzer 레벨 재시도 없음
 *               (Stage2의 callWithSingleRetry 패턴 비답습: 이중 재시도 시 최대 6회 호출 → :free 일 예산 2배 소진).
 * [수정 시 고려사항] 예산 전략 변경 시(조건부 B) skip 조건만 수정 — Analyzer 핵심 로직 무변경.
 *                  Stage 5 추가 시 Stage4Analyzer 패턴 동일 답습(INSERT 아닌 UPDATE, skipIf 조건).
 *                  @Transactional 미사용 의도: findSimilar(Chroma HTTP)·forecast가 느릴 수 있어 DB 커넥션 홀드 방지.
 *                  쓰기는 마지막 resultRepo.save(ar) 1건 — dirty-checking 불필요, save가 원자적.
 */
@Service
public class Stage4Analyzer {

    private static final Logger log = LoggerFactory.getLogger(Stage4Analyzer.class);
    private static final int FORECAST_DAYS = 5;

    private final LlmClient llmClient;
    private final Stage4PromptBuilder promptBuilder;
    private final PromptGuard promptGuard;
    private final Stage3RagService stage3RagService;
    private final PriceReactionForecastService forecastService;
    private final AnalysisResultRepository resultRepo;
    private final LlmProperties props;

    public Stage4Analyzer(LlmClient llmClient,
                          Stage4PromptBuilder promptBuilder,
                          PromptGuard promptGuard,
                          Stage3RagService stage3RagService,
                          PriceReactionForecastService forecastService,
                          AnalysisResultRepository resultRepo,
                          LlmProperties props) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.promptGuard = promptGuard;
        this.stage3RagService = stage3RagService;
        this.forecastService = forecastService;
        this.resultRepo = resultRepo;
        this.props = props;
    }

    /**
     * 단일 공시 Stage 4 분석 — 기존 AnalysisResult를 UPDATE.
     *
     * skip 조건 (결정 C):
     * - Stage 2 분석 결과 없음
     * - withheld=true (판단 보류 상태에서 방향 단정 모순)
     * - 유사 공시 표본 0건 (근거 없는 판단 방지 + 예산 가드)
     * - 이미 stage_reached >= 4 (중복 처리 방지)
     *
     * @return UPDATE된 AnalysisResult. skip/실패 시 Optional.empty()
     */
    public Optional<AnalysisResult> analyze(Long disclosureId) {
        Optional<AnalysisResult> arOpt = resultRepo.findByDisclosureId(disclosureId);
        if (arOpt.isEmpty()) {
            log.debug("Stage4: skip — 분석 결과 없음 disclosureId={}", disclosureId);
            return Optional.empty();
        }
        AnalysisResult ar = arOpt.get();

        if (ar.isWithheld()) {
            log.debug("Stage4: skip — withheld disclosureId={}", disclosureId);
            return Optional.empty();
        }
        if (ar.getStageReached() >= 4) {
            log.debug("Stage4: skip — already stage {} disclosureId={}", ar.getStageReached(), disclosureId);
            return Optional.empty();
        }

        // 결정 (C): 유사 공시 표본 0이면 skip (예산 가드 + 근거 없는 판단 방지)
        List<SimilarDisclosureItem> similar = stage3RagService.findSimilar(disclosureId);
        if (similar.isEmpty()) {
            log.debug("Stage4: skip — 유사 공시 표본 0 disclosureId={}", disclosureId);
            return Optional.empty();
        }

        Optional<PriceReactionForecast> forecast = forecastService.forecast(similar, FORECAST_DAYS);
        String prompt = promptBuilder.build(ar, similar, forecast);

        Stage4Output out;
        try {
            // 재시도는 LlmClient @Retryable(3회)에 단일 위임 — 이중 재시도로 인한 예산 낭비 방지(S-1)
            out = llmClient.classifyStage4(prompt);
        } catch (RestClientException e) {
            log.warn("Stage4: LLM 호출 실패(재시도 소진) — disclosureId={} err={}", disclosureId, e.getMessage());
            return Optional.empty();
        }

        // rationale 자본시장법 가드 — 금지 표현 발견 시 stage 진급 안 함(Stage 2 종결 유지)
        if (promptGuard.isRationaleViolation(out.rationale())) {
            log.warn("Stage4: rationale 금지 키워드 매칭 → skip disclosureId={}", disclosureId);
            return Optional.empty();
        }

        String cappedRationale = Stage4PromptBuilder.capRationale(out.rationale());
        ar.applyStage4(out.expectedReaction(), cappedRationale);
        AnalysisResult saved = resultRepo.save(ar);

        log.info("Stage4 완료: id={} disclosureId={} reaction={} stage={}",
                saved.getId(), disclosureId, saved.getExpectedReaction(), saved.getStageReached());
        return Optional.of(saved);
    }

}
