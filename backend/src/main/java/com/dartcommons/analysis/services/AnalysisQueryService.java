package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.user.entities.UserEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/*
 * [목적] GET /api/v1/disclosures/{id}/analysis — 공시 분석 결과 조회 + 티어별 필드 차등 적용.
 * [이유] 티어 차등(Free/Pro/Premium)은 컨트롤러 레이어 대신 서비스에서 처리해 단일 책임 원칙 유지.
 *       분석 결과가 없으면 404 반환 — FE는 "아직 분석 중" UI로 처리.
 * [사이드 임팩트] AnalysisResponse.from()이 null 필드를 직렬화에서 제외(@JsonInclude(NON_NULL)) — 티어 미달 필드는 JSON에 없음.
 * [수정 시 고려사항] 티어 정보는 JWT principal에서 추출(SecurityContext) — DB 재조회 불필요.
 *                  Stage 3~5 후속 구현 시 tierToStageReached 매핑 로직 추가.
 */
@Service
@RequiredArgsConstructor
public class AnalysisQueryService {

    private final AnalysisResultRepository analysisResultRepository;

    @Transactional(readOnly = true)
    public AnalysisResponse getByDisclosureId(Long disclosureId, UserEntity.Tier tier) {
        var result = analysisResultRepository.findByDisclosureId(disclosureId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "아직 분석이 완료되지 않았습니다."));

        AnalysisResponse.Tier responseTier = switch (tier) {
            case PRO     -> AnalysisResponse.Tier.PRO;
            case PREMIUM -> AnalysisResponse.Tier.PREMIUM;
            default      -> AnalysisResponse.Tier.FREE;
        };

        return AnalysisResponse.from(result, responseTier);
    }
}
