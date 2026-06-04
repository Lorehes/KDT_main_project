package com.dartcommons.analysis.repositories;

import com.dartcommons.analysis.entities.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/*
 * [목적] AnalysisResult CRUD + 미분석 공시 조회 — 백필 잡이 사용.
 * [이유] uq_analysis_disclosure UNIQUE 제약 — disclosureId로 1건 조회/UPSERT.
 *       미분석 공시 페이징 조회는 백필 잡의 청크 처리에 필수.
 * [사이드 임팩트] findUnanalyzedDisclosureIds는 LEFT JOIN으로 풀스캔 가능 — id 범위 조건으로 제한 권장.
 * [수정 시 고려사항] Stage 3~5 후속 Spec에서 stage_reached 별 조회 추가 가능(현재 본 Spec=2 한정).
 */
public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    Optional<AnalysisResult> findByDisclosureId(Long disclosureId);

    boolean existsByDisclosureId(Long disclosureId);

    /**
     * 분석 결과 없는 공시 id 목록(disclosure_id 오름차순).
     * id 범위 + limit으로 청크 단위 처리.
     * idFrom/idTo가 null이면 그쪽 경계 제거.
     */
    @Query("""
            SELECT d.id FROM Disclosure d
            WHERE NOT EXISTS (
                SELECT 1 FROM AnalysisResult ar WHERE ar.disclosureId = d.id
            )
            AND (:idFrom IS NULL OR d.id >= :idFrom)
            AND (:idTo   IS NULL OR d.id <= :idTo)
            ORDER BY d.id ASC
            """)
    List<Long> findUnanalyzedDisclosureIds(
            @Param("idFrom") Long idFrom,
            @Param("idTo") Long idTo,
            org.springframework.data.domain.Pageable pageable
    );

    /** 대상 공시 총 개수 — start() 시 chunksTotal 산출. */
    @Query("""
            SELECT COUNT(d.id) FROM Disclosure d
            WHERE NOT EXISTS (
                SELECT 1 FROM AnalysisResult ar WHERE ar.disclosureId = d.id
            )
            AND (:idFrom IS NULL OR d.id >= :idFrom)
            AND (:idTo   IS NULL OR d.id <= :idTo)
            """)
    long countUnanalyzedDisclosures(
            @Param("idFrom") Long idFrom,
            @Param("idTo") Long idTo
    );
}
