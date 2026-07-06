package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.Stage5Output;
import com.dartcommons.analysis.dto.StageDetailEnvelope;
import com.dartcommons.analysis.dto.StageDetailEnvelope.Stage5Detail;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.infrastructure.llm.LlmClient;
import com.dartcommons.shared.enums.AnalysisStage;
import com.dartcommons.stocks.entities.FinancialSnapshot;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.stocks.repositories.FinancialSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Optional;

/*
 * [목적] Stage 5 재무/업황 분석 단일 진입점 — 재무 스냅샷 + Stage 4 결과를 LLM에 투입해
 *       financialImpact·riskAssessment를 stage_details JSONB에 병합 UPDATE(stage_reached=5).
 * [이유] analysis-stage5-financial-industry Spec R7. Stage4Analyzer 패턴 동일 답습.
 *       Spec 결정 (C): 재무 스냅샷 존재 시에만 실행(게이트 ④).
 *       confidence는 Stage 2 값 보존(단일 신뢰도 소스) — Stage 5 파싱 confidence는 가드용만.
 *       stage_details는 StageDetailEnvelope 래퍼에 stage5 필드 추가 후 재직렬화(하위 호환 유지).
 * [사이드 임팩트] AnalysisResult.stage_details JSONB 업데이트 + stage_reached=5 변경.
 *               기존 Stage 2 stage_details(key_points/요인)는 envelope.stage2로 보존.
 *               PromptGuard.isRationaleViolation으로 financialImpact·riskAssessment 법적 가드.
 *               재시도: LlmClient @Retryable(3회) 단일 위임(Stage4와 동일 — 이중재시도 방지).
 * [수정 시 고려사항] 예산 전략 변경 시(B — 재무 관련 공시 유형 화이트리스트) skip 조건만 수정.
 *                  Stage 6 추가 시 Stage5Analyzer 패턴 동일 답습. AnalysisStage.FINANCIAL=5 상수 사용.
 */
@Service
public class Stage5Analyzer {

    private static final Logger log = LoggerFactory.getLogger(Stage5Analyzer.class);
    /** 프롬프트 입력용 최근 스냅샷 수 — 당기+전기 증감 계산을 위해 2건. */
    private static final int SNAPSHOT_LIMIT = 2;

    private final LlmClient llmClient;
    private final Stage5PromptBuilder promptBuilder;
    private final PromptGuard promptGuard;
    private final FinancialSnapshotRepository snapshotRepo;
    private final AnalysisResultRepository resultRepo;
    private final DisclosureRepository disclosureRepo;
    private final ObjectMapper objectMapper;

    public Stage5Analyzer(LlmClient llmClient,
                          Stage5PromptBuilder promptBuilder,
                          PromptGuard promptGuard,
                          FinancialSnapshotRepository snapshotRepo,
                          AnalysisResultRepository resultRepo,
                          DisclosureRepository disclosureRepo,
                          ObjectMapper objectMapper) {
        this.llmClient = llmClient;
        this.promptBuilder = promptBuilder;
        this.promptGuard = promptGuard;
        this.snapshotRepo = snapshotRepo;
        this.resultRepo = resultRepo;
        this.disclosureRepo = disclosureRepo;
        this.objectMapper = objectMapper;
    }

    /**
     * 단일 공시 Stage 5 분석.
     *
     * skip 조건 (결정 C + 하위 호환):
     * ① Stage 2/4 분석 결과 없음
     * ② withheld=true (판단 보류 상태에서 재무 분석 모순)
     * ③ stage_reached < AnalysisStage.LLM_FINAL (Stage 4 미완)
     * ④ 재무 스냅샷 없음 (예산 가드 + 근거 없는 판단 방지)
     * ⑤ 이미 stage_reached >= AnalysisStage.FINANCIAL (중복 방지)
     *
     * @return UPDATE된 AnalysisResult. skip/실패 시 Optional.empty()
     */
    public Optional<AnalysisResult> analyze(Long disclosureId) {
        Optional<AnalysisResult> arOpt = resultRepo.findByDisclosureId(disclosureId);
        if (arOpt.isEmpty()) {
            log.debug("Stage5: skip — 분석 결과 없음 disclosureId={}", disclosureId);
            return Optional.empty();
        }
        AnalysisResult ar = arOpt.get();

        if (ar.isWithheld()) {
            log.debug("Stage5: skip — withheld disclosureId={}", disclosureId);
            return Optional.empty();
        }
        if (ar.getStageReached() < AnalysisStage.LLM_FINAL) {
            log.debug("Stage5: skip — stage_reached={} < 4 disclosureId={}", ar.getStageReached(), disclosureId);
            return Optional.empty();
        }
        if (ar.getStageReached() >= AnalysisStage.FINANCIAL) {
            log.debug("Stage5: skip — already stage {} disclosureId={}", ar.getStageReached(), disclosureId);
            return Optional.empty();
        }

        // Disclosure의 corp_code 조회 — AnalysisResult는 disclosureId만 보유
        String corpCode = resolveCorpCode(disclosureId);
        if (corpCode == null) {
            log.debug("Stage5: skip — corp_code 조회 실패 disclosureId={}", disclosureId);
            return Optional.empty();
        }

        // 결정 (C): 재무 스냅샷 없으면 skip
        List<FinancialSnapshot> snapshots = snapshotRepo.findRecentByCorpCode(
                corpCode, PageRequest.of(0, SNAPSHOT_LIMIT));
        if (snapshots.isEmpty()) {
            log.debug("Stage5: skip — 재무 스냅샷 없음 corpCode={} disclosureId={}", corpCode, disclosureId);
            return Optional.empty();
        }

        String prompt = promptBuilder.build(ar, snapshots);

        Stage5Output out;
        try {
            out = llmClient.classifyStage5(prompt);
        } catch (RestClientException e) {
            log.warn("Stage5: LLM 호출 실패 — disclosureId={} err={}", disclosureId, e.getMessage());
            return Optional.empty();
        }

        // rationale 가드 — financialImpact·riskAssessment 자본시장법 차단
        if (promptGuard.isRationaleViolation(out.financialImpact())
                || promptGuard.isRationaleViolation(out.riskAssessment())) {
            log.warn("Stage5: PromptGuard 금지 키워드 매칭 → skip disclosureId={}", disclosureId);
            return Optional.empty();
        }

        Stage5Detail stage5Detail = new Stage5Detail(
                Stage5PromptBuilder.capText(out.financialImpact()),
                Stage5PromptBuilder.capText(out.riskAssessment()),
                out.industryContext()   // 현재 null
        );

        // stage_details 래퍼에 stage5 병합 후 재직렬화
        String updatedDetails = mergeStage5(ar.getStageDetails(), stage5Detail);
        ar.applyStage5(updatedDetails);
        AnalysisResult saved = resultRepo.save(ar);

        log.info("Stage5 완료: id={} disclosureId={} corpCode={} stage={}",
                saved.getId(), disclosureId, corpCode, saved.getStageReached());
        return Optional.of(saved);
    }

    private String resolveCorpCode(Long disclosureId) {
        try {
            return disclosureRepo.findCorpCodeByDisclosureId(disclosureId);
        } catch (Exception e) {
            log.warn("Stage5: corp_code 조회 실패 disclosureId={} errType={}", disclosureId, e.getClass().getSimpleName());
            return null;
        }
    }

    private String mergeStage5(String existingDetails, Stage5Detail stage5Detail) {
        StageDetailEnvelope envelope;
        if (existingDetails == null || existingDetails.isBlank()) {
            envelope = new StageDetailEnvelope(null, stage5Detail);
        } else {
            // 1차: StageDetailEnvelope 래퍼 시도
            StageDetailEnvelope parsed = null;
            try {
                parsed = objectMapper.readValue(existingDetails, StageDetailEnvelope.class);
            } catch (Exception ignored) { /* 2차 폴백으로 진행 */ }

            // FAIL_ON_UNKNOWN_PROPERTIES=false라 평면 Stage2Detail을 읽어도 예외 없이 stage2=null이 됨.
            // → stage2가 null이면 래퍼 파싱 성공을 믿지 말고 평면 Stage2Detail 재시도(하위호환 핵심).
            if (parsed != null && parsed.getStage2() != null) {
                envelope = parsed.withStage5(stage5Detail);
            } else {
                // 2차: 평면 Stage2Detail 폴백 (기존 데이터 보호 — Stage5 이전 저장 포맷)
                try {
                    com.dartcommons.analysis.dto.Stage2Detail s2 =
                            objectMapper.readValue(existingDetails, com.dartcommons.analysis.dto.Stage2Detail.class);
                    envelope = new StageDetailEnvelope(s2, stage5Detail);
                } catch (Exception e2) {
                    // stage2 복구 불가 — stage5만 저장(stage2 노출 실종보다 stage5 저장 우선)
                    log.warn("Stage5: 기존 stage_details 파싱 실패 — stage2 유실 가능 disclosureId 불명 errType={}", e2.getClass().getSimpleName());
                    envelope = new StageDetailEnvelope(null, stage5Detail);
                }
            }
        }
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            log.warn("Stage5: stage_details 직렬화 실패 — 기존 유지 errType={}", e.getClass().getSimpleName());
            return existingDetails;
        }
    }

}
