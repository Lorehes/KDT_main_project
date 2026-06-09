package com.dartcommons.disclosure.repositories;

import com.dartcommons.disclosure.entities.Disclosure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/*
 * [목적] disclosures 테이블 CRUD + rcept_no 멱등 체크 + 사용자 공시 피드 조회(JPQL/native 2종씩).
 * [이유] existsByRceptNo가 중복 적재 방어의 애플리케이션 1차 게이트.
 *       DB UNIQUE 제약(V4)은 2차 게이트로 존재해 race condition도 방어.
 *       findFilteredByStocks / findAllFiltered 두 메서드로 분리: Hibernate 6은 JPQL Collection 파라미터에
 *       `:list IS NULL` 패턴을 지원하지 않아 단일 메서드로 scope=all/portfolio를 동시 처리 불가.
 *       findFilteredByStocksWithSentiment / findAllFilteredWithSentiment: sentiment 필터 시 DB 레벨 JOIN.
 *       JPQL은 Disclosure-AnalysisResult 간 매핑 없어 native query 사용(fe-correctness-investor-protection R3).
 * [사이드 임팩트] existsByRceptNo는 SELECT COUNT 쿼리 — 고빈도 폴링에서 인덱스(rcept_no UNIQUE) 의존.
 *               호출부(DisclosureQueryService)에서 stockCodes == null 여부로 메서드를 분기해야 함.
 *               Pageable은 Sort 없이(PageRequest.of(page, size)) 전달 — ORDER BY와 이중 적용 방지.
 * [수정 시 고려사항] native query는 PostgreSQL ANSI SQL — dialect 전환 시 재검토 필요.
 *                  (stock_code, rcept_dt DESC) 복합 인덱스 추가는 performance-caching-staletime Spec 위임.
 *                  sentiment IS NULL 패턴: sentiment=null → 전체 공시 반환(필터 미적용). 정상 동작.
 */
public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    /** rcept_no가 이미 존재하는지 확인. 중복 발송 방어 1차 게이트(CLAUDE.md §4). */
    boolean existsByRceptNo(String rceptNo);

    /**
     * 공시 id → stock_code 단일 컬럼 조회. 포트폴리오 소유권 검증에서 전체 엔티티 로드를 피하기 위해 사용.
     * 비상장 공시(stock_code IS NULL)는 Optional.of(null) 또는 Optional.empty() — 호출자에서 null 체크 필수.
     */
    @Query("SELECT d.stockCode FROM Disclosure d WHERE d.id = :id")
    Optional<String> findStockCodeById(@Param("id") Long id);

    /**
     * 포트폴리오 종목 필터 조회 (scope=portfolio).
     * stockCodes 리스트가 비어있으면 호출하지 말 것 — 서비스 계층에서 사전 가드.
     */
    @Query(value = """
            SELECT d FROM Disclosure d
            WHERE d.stockCode IN :stockCodes
              AND (:fromDate IS NULL OR d.rceptDt >= :fromDate)
              AND (:toDate IS NULL OR d.rceptDt <= :toDate)
            ORDER BY d.rceptDt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d) FROM Disclosure d
            WHERE d.stockCode IN :stockCodes
              AND (:fromDate IS NULL OR d.rceptDt >= :fromDate)
              AND (:toDate IS NULL OR d.rceptDt <= :toDate)
            """)
    Page<Disclosure> findFilteredByStocks(
            @Param("stockCodes") List<String> stockCodes,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    /**
     * 전체 공시 조회 (scope=all).
     * 종목코드 필터 없이 날짜 범위만 적용.
     */
    @Query(value = """
            SELECT d FROM Disclosure d
            WHERE (:fromDate IS NULL OR d.rceptDt >= :fromDate)
              AND (:toDate IS NULL OR d.rceptDt <= :toDate)
            ORDER BY d.rceptDt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d) FROM Disclosure d
            WHERE (:fromDate IS NULL OR d.rceptDt >= :fromDate)
              AND (:toDate IS NULL OR d.rceptDt <= :toDate)
            """)
    Page<Disclosure> findAllFiltered(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            Pageable pageable
    );

    /**
     * 포트폴리오 종목 + sentiment 필터 조회 — DB 레벨 LEFT JOIN (fe-correctness-investor-protection R3).
     * sentiment=null → 전체 반환. sentiment='POSITIVE' 등 → 해당 감성 분析 결과가 있는 공시만 반환.
     * analysis_results 미존재 공시는 sentiment 필터 적용 시 제외됨(투자자 의도에 부합).
     */
    @Query(value = """
            SELECT d.* FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE d.stock_code IN :stockCodes
              AND (:fromDate IS NULL OR d.rcept_dt >= :fromDate)
              AND (:toDate IS NULL OR d.rcept_dt <= :toDate)
              AND (:sentiment IS NULL OR ar.sentiment = :sentiment)
            ORDER BY d.rcept_dt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d.id) FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE d.stock_code IN :stockCodes
              AND (:fromDate IS NULL OR d.rcept_dt >= :fromDate)
              AND (:toDate IS NULL OR d.rcept_dt <= :toDate)
              AND (:sentiment IS NULL OR ar.sentiment = :sentiment)
            """,
            nativeQuery = true)
    Page<Disclosure> findFilteredByStocksWithSentiment(
            @Param("stockCodes") List<String> stockCodes,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("sentiment") String sentiment,
            Pageable pageable
    );

    /**
     * 전체 공시 + sentiment 필터 조회 (scope=all) — DB 레벨 LEFT JOIN.
     * sentiment=null → 전체 반환. 종목코드 필터 없음.
     */
    @Query(value = """
            SELECT d.* FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE (:fromDate IS NULL OR d.rcept_dt >= :fromDate)
              AND (:toDate IS NULL OR d.rcept_dt <= :toDate)
              AND (:sentiment IS NULL OR ar.sentiment = :sentiment)
            ORDER BY d.rcept_dt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d.id) FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE (:fromDate IS NULL OR d.rcept_dt >= :fromDate)
              AND (:toDate IS NULL OR d.rcept_dt <= :toDate)
              AND (:sentiment IS NULL OR ar.sentiment = :sentiment)
            """,
            nativeQuery = true)
    Page<Disclosure> findAllFilteredWithSentiment(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("sentiment") String sentiment,
            Pageable pageable
    );
}
