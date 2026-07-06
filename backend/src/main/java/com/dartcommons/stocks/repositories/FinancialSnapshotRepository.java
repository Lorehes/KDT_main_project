package com.dartcommons.stocks.repositories;

import com.dartcommons.stocks.entities.FinancialSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/*
 * [목적] financial_snapshots CRUD + Stage 5 분석에 필요한 조회 — FinancialSyncService·Stage5Analyzer 공용.
 * [이유] (corp_code, bsns_year, reprt_code) UNIQUE → existsBy 체크로 멱등 UPSERT.
 *       최근 N개 스냅샷 조회는 Stage5PromptBuilder 입력 조립에 사용(전기 대비 증감 계산).
 * [사이드 임팩트] analysis 도메인(Stage5Analyzer)이 이 리포지토리를 read-only로 직접 참조.
 *               CLAUDE.md §3-2 마스터 데이터 예외: write는 stocks 도메인 한정.
 * [수정 시 고려사항] Stage 5 프롬프트가 더 긴 기간(>8분기)을 필요로 하면 findRecentN limit 확장.
 */
public interface FinancialSnapshotRepository extends JpaRepository<FinancialSnapshot, Long> {

    boolean existsByCorpCodeAndBsnsYearAndReprtCode(String corpCode, String bsnsYear, String reprtCode);

    Optional<FinancialSnapshot> findByCorpCodeAndBsnsYearAndReprtCode(String corpCode, String bsnsYear, String reprtCode);

    /**
     * 특정 회사의 최근 N분기 스냅샷 — 최신순 정렬.
     * Stage5PromptBuilder가 입력 조립 시 전기 비교용으로 사용.
     */
    @Query("""
            SELECT f FROM FinancialSnapshot f
            WHERE f.corpCode = :corpCode
            ORDER BY f.bsnsYear DESC, f.reprtCode DESC
            """)
    List<FinancialSnapshot> findRecentByCorpCode(@Param("corpCode") String corpCode,
                                                  org.springframework.data.domain.Pageable pageable);
}
