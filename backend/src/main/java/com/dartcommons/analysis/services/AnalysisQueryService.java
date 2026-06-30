package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.shared.enums.Tier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/*
 * [목적] GET /api/v1/disclosures/{id}/analysis — 공시 분석 결과 조회 + 티어별 필드 차등 적용.
 *       Pro+ 티어는 Stage 3 RAG 유사 공시(Stage3RagService.findSimilar)를 실시간으로 조회해 응답에 포함.
 * [이유] 티어 차등(Free/Pro/Premium)은 컨트롤러 레이어 대신 서비스에서 처리해 단일 책임 원칙 유지.
 *       Stage 3 결과를 AnalysisResult 엔티티에 저장하지 않고 조회 시 실시간 쿼리 — Chroma가 SSOT.
 *       분석 결과가 없으면 404 반환 — FE는 "아직 분석 중" UI로 처리.
 *       AnalysisResultCacheService 경유 조회 — Caffeine 캐시(10분/10_000 항목) 활용.
 * [사이드 임팩트] findSimilar()는 Chroma 비활성(MockChromaClient) 시 빈 리스트 반환 — similar=[]이 아닌 null이 됨
 *               (Free 응답과 구분 불가) — 향후 Chroma 활성 여부를 별도 플래그로 FE에 알릴 수 있음.
 *               findSimilar() 내부 예외는 Stage3RagService에서 warn 로그 후 빈 리스트 반환 — 쿼리 실패가 전체 응답을 차단하지 않음.
 * [수정 시 고려사항] Stage 3 결과 캐싱이 필요하면 AnalysisResultCacheService와 동일한 Caffeine 캐시 적용 검토.
 *                  findSimilar가 느릴 경우(Ollama 임베딩 콜드스타트 등) 타임아웃을 ChromaProperties.timeoutMs로 제어.
 */
@Service
@RequiredArgsConstructor
public class AnalysisQueryService {

    private final AnalysisResultCacheService analysisResultCacheService;
    private final Stage3RagService stage3RagService;

    public AnalysisResponse getByDisclosureId(Long disclosureId, Tier tier) {
        var result = analysisResultCacheService.findByDisclosureId(disclosureId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "아직 분석이 완료되지 않았습니다.");
        }

        boolean proPlus = tier == Tier.PRO || tier == Tier.PREMIUM;
        List<SimilarDisclosureItem> similar = proPlus
                ? stage3RagService.findSimilar(disclosureId)
                : null;

        // similar가 빈 리스트면 null로 변환 — @JsonInclude(NON_NULL)로 JSON 필드 제외
        if (similar != null && similar.isEmpty()) {
            similar = null;
        }

        return AnalysisResponse.from(result, tier, similar);
    }
}
