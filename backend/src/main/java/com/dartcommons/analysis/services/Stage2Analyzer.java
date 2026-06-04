package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.Stage2Output;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.llm.LlmClient;
import com.dartcommons.infrastructure.llm.LlmProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/*
 * [목적] Stage 2 LLM 1차 분석의 단일 진입점 — 공시 1건을 받아 LLM 호출 + 파싱 + 가드 + UPSERT.
 *       Orchestrator(이벤트 리스너)와 BackfillService 양쪽에서 호출.
 * [이유] analysis-stage2-llm Spec §6.1 핵심 시퀀스. LLM 호출 + 파싱 재시도 + PromptGuard + 저장의 책임 통합.
 *       LLM 응답 record 강제 파싱(CLAUDE.md §6-6) + 자본시장법 가드(§7) + 신뢰도 임계 보류(api_spec §2.4).
 * [사이드 임팩트] UPSERT — uq_analysis_disclosure UNIQUE로 공시당 1건. 재호출은 새 분석으로 덮어쓰지 않고 skip.
 *               파싱 재시도 1회(spec): 첫 호출 RestClientException → 1회 재호출. 그래도 실패면 stage_reached=1 유지 + WARN.
 *               LLM provider 미설정 또는 통신 실패는 무해 skip(폴링/백필이 멈추지 않도록).
 * [수정 시 고려사항] 신뢰도 임계치는 LlmProperties.confidenceThreshold(application.yml 또는 SystemConfig).
 *                  Stage 3+ 진입은 별도 Spec — 본 Analyzer는 stage_reached=2 한정.
 *                  토큰 사용량 영속화(input_tokens/output_tokens)는 OllamaLlmClient가 반환 메타 노출 시 후속.
 */
@Service
public class Stage2Analyzer {

    private static final Logger log = LoggerFactory.getLogger(Stage2Analyzer.class);

    private final LlmClient llmClient;
    private final Stage2PromptBuilder promptBuilder;
    private final PromptGuard promptGuard;
    private final AnalysisResultRepository resultRepo;
    private final DisclosureRepository disclosureRepo;
    private final LlmProperties props;

    public Stage2Analyzer(LlmClient llmClient,
                          Stage2PromptBuilder promptBuilder,
                          PromptGuard promptGuard,
                          AnalysisResultRepository resultRepo,
                          DisclosureRepository disclosureRepo,
                          LlmProperties props) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.promptGuard = promptGuard;
        this.resultRepo = resultRepo;
        this.disclosureRepo = disclosureRepo;
        this.props = props;
    }

    /**
     * 단일 공시 분석 — 이미 분석된 공시는 skip(idempotent).
     *
     * 흐름:
     * 1. existsByDisclosureId 체크 → skip
     * 2. Disclosure 조회 → 프롬프트 빌드
     * 3. LLM 호출 (재시도 1회 — 파싱 실패 / 통신 실패 동일하게 RestClientException으로 묶음)
     * 4. PromptGuard sanitize (금지 키워드 + 신뢰도 임계 + 글자수 cap)
     * 5. AnalysisResult INSERT
     *
     * @return 저장된 AnalysisResult Optional. 실패/skip 시 Optional.empty()
     */
    @Transactional
    public Optional<AnalysisResult> analyze(Long disclosureId) {
        if (resultRepo.existsByDisclosureId(disclosureId)) {
            log.debug("Stage2: skip already analyzed disclosureId={}", disclosureId);
            return Optional.empty();
        }

        Optional<Disclosure> dOpt = disclosureRepo.findById(disclosureId);
        if (dOpt.isEmpty()) {
            log.warn("Stage2: disclosure not found disclosureId={}", disclosureId);
            return Optional.empty();
        }
        Disclosure d = dOpt.get();

        String prompt = promptBuilder.build(d);

        Stage2Output rawOutput;
        try {
            rawOutput = callWithSingleRetry(prompt);
        } catch (RestClientException e) {
            // 2회(=초기 + 재시도 1회) 모두 실패 — silent fail로 stage_reached=1 유지.
            // 사용자 응답은 "분석 준비 중" 처리. 운영 로그로 추적.
            log.warn("Stage2: LLM 호출/파싱 2회 실패 — disclosureId={} corpName={} err={}",
                    disclosureId, d.getCorpName(), e.getMessage());
            return Optional.empty();
        }

        PromptGuard.GuardResult guarded = promptGuard.sanitize(rawOutput, props.confidenceThreshold());
        if (guarded.forbiddenHit()) {
            log.warn("Stage2: 금지 키워드 매칭 → withheld 강제 disclosureId={} corpName={}",
                    disclosureId, d.getCorpName());
        }

        AnalysisResult result = AnalysisResult.builder()
                .disclosureId(disclosureId)
                .sentiment(guarded.sanitized().sentiment())
                .confidence(guarded.sanitized().confidence())
                .withheld(guarded.withheld())
                .summary(guarded.sanitized().summary())
                .stageReached((short) 2)
                .modelName(props.model())
                .build();

        AnalysisResult saved = resultRepo.save(result);
        log.info("Stage2 완료: id={} disclosureId={} sentiment={} confidence={} withheld={}",
                saved.getId(), disclosureId, saved.getSentiment(), saved.getConfidence(), saved.isWithheld());
        return Optional.of(saved);
    }

    /**
     * 첫 호출 실패(RestClientException) 시 1회 재호출.
     * LlmClient 구현체 자체에 @Retryable이 있어도, Spec은 파싱 실패까지 묶어 "분석기 레벨 1회 재시도"를 요구.
     * 분석기 재시도는 LLM 입출력 결정론성에 의존 — 같은 프롬프트로 다른 응답을 받을 가능성에 베팅.
     */
    private Stage2Output callWithSingleRetry(String prompt) {
        try {
            return llmClient.classifyStage2(prompt);
        } catch (RestClientException first) {
            log.debug("Stage2: 첫 호출 실패, 1회 재시도 — err={}", first.getMessage());
            return llmClient.classifyStage2(prompt);
        }
    }
}
