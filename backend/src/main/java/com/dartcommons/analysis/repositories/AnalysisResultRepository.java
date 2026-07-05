package com.dartcommons.analysis.repositories;

import com.dartcommons.analysis.entities.AnalysisResult;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/*
 * [목적] AnalysisResult CRUD + 미분석 공시 조회 — 백필 잡이 사용.
 * [이유] uq_analysis_disclosure UNIQUE 제약 — disclosureId로 1건 조회/UPSERT.
 *       미분석 공시 페이징 조회는 백필 잡의 청크 처리에 필수.
 *       캐싱은 AnalysisResultCacheService(별도 빈)에서 담당 — Spring Data Optional 반환 메서드에 @Cacheable 직접 적용 시
 *       Spring Data JPA 프록시가 Optional을 언랩해 SpEL #result가 T(엔티티) 타입으로 전달되는 호환 문제 회피.
 * [사이드 임팩트] findUnanalyzedDisclosureIds는 LEFT JOIN으로 풀스캔 가능 — id 범위 조건으로 제한 권장.
 *               findByDisclosureIdIn(bulk)은 캐시 미적용 — 목록 피드 경로는 TTL 별도 검토 필요(현재 무캐시).
 * [수정 시 고려사항] Stage 3~5 후속 Spec에서 stage_reached 별 조회 추가 가능(현재 본 Spec=2 한정).
 *                  재분석 시 AnalysisResultCacheService.evict(disclosureId) 호출 필요 — ReanalysisService 참조.
 *                  deleteByDisclosureIdIn + feedbacks ON DELETE CASCADE: feedbacks 0인 현 MVP는 무손실.
 *                  feedbacks 존재하는 프로드 대비 시 접근 B(UPDATE overwrite) 검토 — reanalyze-after-charset-recollection §결정1.
 */
public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, Long> {

    Optional<AnalysisResult> findByDisclosureId(Long disclosureId);

    boolean existsByDisclosureId(Long disclosureId);

    /** 공시 목록 응답 생성용 bulk 조회 — N+1 쿼리 방지. */
    List<AnalysisResult> findByDisclosureIdIn(Collection<Long> disclosureIds);

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

    /**
     * 재분석 대상 공시 id 목록 — content_fetched_at >= since(재수집 시점) ∧ 분석 결과 존재.
     * reanalyze-after-charset-recollection: 손상 본문 기반 analysis_results를 청크 단위로 선삭제→재분석.
     * since=2026-07-03T00:00Z 적용 시 실측 7,284건.
     */
    @Query("""
            SELECT ar.disclosureId FROM AnalysisResult ar
            JOIN Disclosure d ON d.id = ar.disclosureId
            WHERE d.contentFetchedAt >= :since
            AND (:watermark IS NULL OR ar.disclosureId >= :watermark)
            ORDER BY ar.disclosureId ASC
            """)
    List<Long> findReanalysisTargetIds(
            @Param("since") OffsetDateTime since,
            @Param("watermark") Long watermark,
            org.springframework.data.domain.Pageable pageable
    );

    /** 재분석 대상 총 건수 — 잡 시작 시 targeted 산출. */
    @Query("""
            SELECT COUNT(ar.disclosureId) FROM AnalysisResult ar
            JOIN Disclosure d ON d.id = ar.disclosureId
            WHERE d.contentFetchedAt >= :since
            """)
    long countReanalysisTargets(@Param("since") OffsetDateTime since);

    /**
     * 분석 결과 일괄 삭제 — 재분석 전 선삭제(접근 A).
     * feedbacks.analysis_id ON DELETE CASCADE: feedbacks 0인 MVP는 무손실.
     * @Modifying + clearAutomatically: 영속성 컨텍스트와 DB 상태 정합.
     */
    @Modifying(clearAutomatically = true)
    @Query("DELETE FROM AnalysisResult ar WHERE ar.disclosureId IN :ids")
    void deleteByDisclosureIdIn(@Param("ids") Collection<Long> ids);

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

    /**
     * Stage 4 백필 대상 — stage_reached=2이고 withheld=false인 분석 결과 id 목록(커서 페이지네이션).
     * Stage 4 skip 조건(withheld, 유사표본 없음)은 Stage4Analyzer 내부에서 처리.
     * analysis-stage4-llm-final Spec 결정 (C): 유사표본 없는 건은 Analyzer skip — 여기서 미리 필터링 안 함.
     */
    @Query("""
            SELECT ar.disclosureId FROM AnalysisResult ar
            WHERE ar.stageReached = 2
            AND ar.withheld = false
            AND (:lastId IS NULL OR ar.disclosureId > :lastId)
            ORDER BY ar.disclosureId ASC
            """)
    List<Long> findStage4BackfillTargets(
            @Param("lastId") Long lastId,
            org.springframework.data.domain.Pageable pageable
    );

    /** Stage 4 백필 대상 총 건수 (근사치, safetyCap 산출용). */
    @Query("SELECT COUNT(ar) FROM AnalysisResult ar WHERE ar.stageReached = 2 AND ar.withheld = false")
    long countStage4BackfillTargets();
}
