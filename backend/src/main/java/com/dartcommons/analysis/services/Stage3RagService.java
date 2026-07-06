package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.chroma.ChromaClient;
import com.dartcommons.infrastructure.chroma.ChromaClient.SimilarResult;
import com.dartcommons.infrastructure.chroma.ChromaProperties;
import com.dartcommons.infrastructure.llm.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/*
 * [목적] Stage 3 RAG 핵심 서비스 — 공시 본문 임베딩 upsert + 이중 쿼리 유사 공시 검색.
 *       upsert(Long): 분석 완료 시 공시 본문을 Chroma에 저장 (멱등, rceptNo 기반).
 *       findSimilar(Long): 조회 시 동일 유형 유사 공시를 이중 쿼리로 검색.
 * [이유] analysis 도메인이 ChromaClient/EmbeddingClient 인터페이스만 의존 — LangChain4j SDK는 infrastructure 레이어 격리.
 *       이중 쿼리: 단일 쿼리(동일 유형 max10) 후 Java에서 same-corp(max5) / other-corp(max5) 파티셔닝.
 *       Chroma filter 부정 조건(not equals)이 버전에 따라 불안정하므로 Java 포스트 필터로 분리.
 *       similarityThreshold(기본 0.7): 저유사도 강제 매칭 방지 — 임계치 미만은 결과에서 제외(환각 가드 R8).
 * [사이드 임팩트] upsert()는 AnalysisOrchestrator에서 동기 호출 — analysisExecutor 풀에서 Ollama 임베딩 호출.
 *               Ollama 미기동 시 upsert 예외 → AnalysisOrchestrator catch에서 warn 로그 후 publishCompleted 계속.
 *               MockEmbeddingClient + MockChromaClient 기본값이므로 로컬/CI에서는 Stage 3 가 실제로 동작하지 않음
 *               (findSimilar가 빈 리스트 반환) — EMBEDDING_PROVIDER=ollama + CHROMA_ENABLED=true 필요.
 * [수정 시 고려사항] 이중 쿼리 결과(max10)는 자신(rceptNo) 제외 후 파티셔닝 — 자신이 결과에 포함되면 유사도 1.0이라 제외 필수.
 *                  disclosureType이 "OTHER"(분류 실패)인 경우 쿼리 결과가 뒤섞임 — 향후 타입 분류 개선 시 함께 검토.
 *                  Chroma 컬렉션 차원(768)과 EmbeddingClient 모델 차원 불일치 시 400 오류 — 모델 교체 시 컬렉션 재생성 필요.
 */
@Service
@RequiredArgsConstructor
public class Stage3RagService {

    private static final Logger log = LoggerFactory.getLogger(Stage3RagService.class);
    private static final int DUAL_QUERY_LIMIT = 10;
    private static final int PARTITION_LIMIT = 5;

    private final EmbeddingClient embeddingClient;
    private final ChromaClient chromaClient;
    private final DisclosureRepository disclosureRepository;
    private final ChromaProperties chromaProperties;

    /**
     * 공시 본문을 임베딩해 Chroma에 upsert — Stage 2 완료 후 AnalysisOrchestrator에서 호출.
     * contentText 없으면 스킵. 동일 rceptNo 재호출은 멱등(덮어씀).
     */
    public void upsert(Long disclosureId) {
        Optional<Disclosure> opt = disclosureRepository.findById(disclosureId);
        if (opt.isEmpty()) {
            log.debug("Stage3.upsert skip — disclosure not found: id={}", disclosureId);
            return;
        }
        Disclosure d = opt.get();

        float[] vector = embeddingClient.embed(embedText(d));
        chromaClient.upsert(d.getRceptNo(), vector, buildMetadata(d));
        log.debug("Stage3.upsert OK: rceptNo={} corpCode={} type={}",
                d.getRceptNo(), d.getCorpCode(), d.getDisclosureType());
    }

    /**
     * 이중 쿼리로 유사 공시 검색.
     * ① 동일 유형 max10 검색 → sameCorp max5 + otherCorp max5 파티셔닝 → 유사도 내림차순.
     * contentText 없으면 corpName+disclosureType+reportNm 폴백 텍스트로 임베딩.
     */
    public List<SimilarDisclosureItem> findSimilar(Long disclosureId) {
        Optional<Disclosure> opt = disclosureRepository.findById(disclosureId);
        if (opt.isEmpty()) return List.of();

        Disclosure d = opt.get();

        float[] queryVector;
        try {
            queryVector = embeddingClient.embed(embedText(d));
        } catch (Exception e) {
            log.warn("Stage3.findSimilar embed 실패: disclosureId={} err={}", disclosureId, e.getMessage());
            return List.of();
        }

        List<SimilarResult> raw;
        try {
            raw = chromaClient.query(
                    queryVector,
                    Map.of("disclosure_type", d.getDisclosureType()),
                    DUAL_QUERY_LIMIT,
                    chromaProperties.similarityThreshold()
            );
        } catch (Exception e) {
            log.warn("Stage3.findSimilar query 실패: disclosureId={} err={}", disclosureId, e.getMessage());
            return List.of();
        }

        // 자신 제외
        List<SimilarResult> filtered = raw.stream()
                .filter(r -> !d.getRceptNo().equals(r.id()))
                .toList();

        List<SimilarResult> sameCorp = filtered.stream()
                .filter(r -> d.getCorpCode().equals(r.metadata().get("corp_code")))
                .limit(PARTITION_LIMIT)
                .toList();

        List<SimilarResult> otherCorp = filtered.stream()
                .filter(r -> !d.getCorpCode().equals(r.metadata().get("corp_code")))
                .limit(PARTITION_LIMIT)
                .toList();

        return Stream.concat(sameCorp.stream(), otherCorp.stream())
                .sorted(Comparator.comparingDouble(SimilarResult::score).reversed())
                .map(Stage3RagService::toItem)
                .toList();
    }

    private static SimilarDisclosureItem toItem(SimilarResult r) {
        Map<String, String> m = r.metadata();
        String rawId = m.get("disclosure_id");
        Long disclosureId = null;
        if (rawId != null) {
            try { disclosureId = Long.parseLong(rawId); }
            catch (NumberFormatException ignored) {
                log.warn("Stage3: disclosure_id 메타데이터 파싱 실패 — rawId={}", rawId);
            }
        }
        return new SimilarDisclosureItem(
                disclosureId,
                r.id(),                                    // rcept_no
                m.getOrDefault("corp_name", ""),
                m.getOrDefault("corp_code", ""),
                m.getOrDefault("disclosure_type", ""),
                m.getOrDefault("rcept_dt", ""),
                r.score()
        );
    }

    // ponytail: contentText 없으면 메타데이터 단문 폴백 — 품질은 낮지만 Stage3/4 활성화
    private static String embedText(Disclosure d) {
        if (d.getContentText() != null && !d.getContentText().isBlank()) return d.getContentText();
        return d.getCorpName() + " " + d.getDisclosureType() + " " + d.getReportNm();
    }

    private static Map<String, String> buildMetadata(Disclosure d) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("disclosure_id", String.valueOf(d.getId()));
        m.put("corp_code", d.getCorpCode());
        m.put("corp_name", d.getCorpName());
        m.put("disclosure_type", d.getDisclosureType());
        m.put("rcept_dt", d.getRceptDt().toString());
        return m;
    }
}
