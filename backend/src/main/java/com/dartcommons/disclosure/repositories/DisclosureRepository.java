package com.dartcommons.disclosure.repositories;

import com.dartcommons.disclosure.entities.Disclosure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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
 *                  날짜 파라미터 null 처리: PostgreSQL extended protocol은 parse 단계에서 파라미터 타입 OID를 결정.
 *                  `:param IS NULL` 패턴의 null 파라미터는 OID 미지정 시 "could not determine data type" 오류 발생.
 *                  JPQL: COALESCE(:fromDate, d.rceptDt) 사용 → null 시 d.rceptDt와 비교(항상 true, 필터 없음).
 *                  native: CAST(:fromDate AS date) IS NULL 사용 → PostgreSQL이 CAST에서 타입 결정.
 *                  q 파라미터: JPQL에서는 `:q IS NULL` 패턴이 OID 문제 없이 동작(String 타입 추론). LOWER+LIKE 사용.
 *                  native에서는 CAST(:q AS text) IS NULL 패턴 필수 — ILIKE '%' || :q || '%' 로 대소문자 무시.
 *                  성능: q 검색은 full table scan — MVP 수용. 추후 pg_trgm GIN 인덱스로 최적화(별도 Flyway 마이그레이션).
 *                  알려진 한계: LIKE 메타 문자('%', '_') 포함 q 입력 시 예상 외 결과 반환 가능 — MVP 수용.
 *                  이스케이프 필요 시 서비스 계층에서 `q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")` 추가 후 ESCAPE '\\' 절 사용.
 */
public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    /** rcept_no가 이미 존재하는지 확인. 중복 발송 방어 1차 게이트(CLAUDE.md §4). */
    boolean existsByRceptNo(String rceptNo);

    /**
     * 본문 미fetch 공시 ID 청크 — 커서 기반 페이지네이션(content-fetch-backfill-pagination Spec).
     * lastId=null이면 전체 처음부터, lastId 지정 시 해당 id 이후(id > lastId) 조회.
     * ORDER BY id ASC + PK 인덱스 range scan — 부분 인덱스(V24 idx_disclosures_content_pending)가 필터 보조.
     * DisclosureContentBackfillService 커서 루프의 유일한 조회 지점.
     */
    @Query("SELECT d.id FROM Disclosure d WHERE d.contentFetchedAt IS NULL AND (:lastId IS NULL OR d.id > :lastId) ORDER BY d.id ASC")
    List<Long> findPendingContentFetchIds(@Param("lastId") Long lastId, Pageable pageable);

    /**
     * content_fetched_at IS NULL인 공시 총 건수 — 백필 시작 시 estimated total 산출.
     * safety cap 계산용이므로 정밀도보다 빠른 스캔이 중요. 부분 인덱스(V24) 활용.
     */
    @Query("SELECT COUNT(d.id) FROM Disclosure d WHERE d.contentFetchedAt IS NULL")
    long countPendingContentFetch();

    /**
     * content_text 보유 공시 ID 청크 — Stage 3 임베딩 백필 커서 페이지네이션(stage3-embedding-backfill Spec).
     * lastId=null이면 처음부터, lastId 지정 시 해당 id 이후(id > lastId) 조회. ORDER BY id ASC.
     */
    @Query("SELECT d.id FROM Disclosure d WHERE d.contentText IS NOT NULL AND (:lastId IS NULL OR d.id > :lastId) ORDER BY d.id ASC")
    List<Long> findIdsWithContentText(@Param("lastId") Long lastId, Pageable pageable);

    /** content_text 보유 공시 총 건수 — 임베딩 백필 estimated total / safetyCap 산출용. */
    @Query("SELECT COUNT(d.id) FROM Disclosure d WHERE d.contentText IS NOT NULL")
    long countWithContentText();

    /**
     * content_text + content_fetched_at을 원자적으로 갱신 — CAS 조건: content_fetched_at IS NULL.
     * HIGH-3(TOCTOU) 수정: @Transactional 없는 외부 메서드에서 동시 호출 시 DB 수준 원자성 보장.
     * 반환값 1 = 갱신 성공, 0 = 다른 스레드가 선점(정상 — no-op).
     * 영구 실패(DartApiException) 시에도 text=null + fetchedAt=now로 호출해 재시도 방지.
     */
    @Modifying
    @Transactional
    @Query("UPDATE Disclosure d SET d.contentText = :text, d.contentFetchedAt = :fetchedAt WHERE d.id = :id AND d.contentFetchedAt IS NULL")
    int casUpdateContent(@Param("id") Long id, @Param("text") String text, @Param("fetchedAt") java.time.OffsetDateTime fetchedAt);

    /**
     * 공시 id → stock_code 단일 컬럼 조회. 포트폴리오 소유권 검증에서 전체 엔티티 로드를 피하기 위해 사용.
     * 비상장 공시(stock_code IS NULL)는 Optional.of(null) 또는 Optional.empty() — 호출자에서 null 체크 필수.
     */
    @Query("SELECT d.stockCode FROM Disclosure d WHERE d.id = :id")
    Optional<String> findStockCodeById(@Param("id") Long id);

    /** Stage5Analyzer에서 재무 스냅샷 조회용 corp_code 역조회 — read-only. */
    @Query("SELECT d.corpCode FROM Disclosure d WHERE d.id = :id")
    String findCorpCodeByDisclosureId(@Param("id") Long id);

    /**
     * 포트폴리오 종목 필터 조회 (scope=portfolio).
     * stockCodes 리스트가 비어있으면 호출하지 말 것 — 서비스 계층에서 사전 가드.
     */
    @Query(value = """
            SELECT d.* FROM disclosures d
            WHERE d.stock_code IN :stockCodes
              AND (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            ORDER BY d.rcept_dt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d.id) FROM disclosures d
            WHERE d.stock_code IN :stockCodes
              AND (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            """,
            nativeQuery = true)
    Page<Disclosure> findFilteredByStocks(
            @Param("stockCodes") List<String> stockCodes,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("q") String q,
            Pageable pageable
    );

    /**
     * 전체 공시 조회 (scope=all).
     * 종목코드 필터 없이 날짜 범위만 적용.
     */
    @Query(value = """
            SELECT d.* FROM disclosures d
            WHERE (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            ORDER BY d.rcept_dt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d.id) FROM disclosures d
            WHERE (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            """,
            nativeQuery = true)
    Page<Disclosure> findAllFiltered(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("q") String q,
            Pageable pageable
    );

    /**
     * 포트폴리오 종목 + sentiment/withheld 필터 조회 — DB 레벨 LEFT JOIN (fe-correctness-investor-protection R3).
     * sentiment=null → 감성 필터 미적용. sentiment='POSITIVE' 등 → 해당 감성 분析 결과가 있는 공시만 반환.
     * withheld=null → 보류 필터 미적용. withheld=true → is_withheld=true(판단 보류) 공시만. false → 보류 제외.
     * 보류(is_withheld)는 sentiment 값이 아닌 별도 플래그 — 보류 필터 시 sentiment=null로 호출(disclosure-withheld-filter).
     * analysis_results 미존재 공시는 sentiment/withheld 필터 적용 시 제외됨(투자자 의도에 부합).
     */
    @Query(value = """
            SELECT d.* FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE d.stock_code IN :stockCodes
              AND (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:sentiment AS text) IS NULL OR ar.sentiment = :sentiment)
              AND (CAST(:withheld AS boolean) IS NULL OR ar.is_withheld = :withheld)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            ORDER BY d.rcept_dt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d.id) FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE d.stock_code IN :stockCodes
              AND (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:sentiment AS text) IS NULL OR ar.sentiment = :sentiment)
              AND (CAST(:withheld AS boolean) IS NULL OR ar.is_withheld = :withheld)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            """,
            nativeQuery = true)
    Page<Disclosure> findFilteredByStocksWithSentiment(
            @Param("stockCodes") List<String> stockCodes,
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("sentiment") String sentiment,
            @Param("withheld") Boolean withheld,
            @Param("q") String q,
            Pageable pageable
    );

    /**
     * 전체 공시 + sentiment/withheld 필터 조회 (scope=all) — DB 레벨 LEFT JOIN.
     * sentiment=null → 감성 필터 미적용. withheld=null → 보류 필터 미적용. 종목코드 필터 없음.
     */
    @Query(value = """
            SELECT d.* FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:sentiment AS text) IS NULL OR ar.sentiment = :sentiment)
              AND (CAST(:withheld AS boolean) IS NULL OR ar.is_withheld = :withheld)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            ORDER BY d.rcept_dt DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(d.id) FROM disclosures d
            LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id
            WHERE (CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate)
              AND (CAST(:toDate AS date) IS NULL OR d.rcept_dt <= :toDate)
              AND (CAST(:sentiment AS text) IS NULL OR ar.sentiment = :sentiment)
              AND (CAST(:withheld AS boolean) IS NULL OR ar.is_withheld = :withheld)
              AND (CAST(:q AS text) IS NULL OR d.report_nm ILIKE '%' || :q || '%' OR d.corp_name ILIKE '%' || :q || '%')
            """,
            nativeQuery = true)
    Page<Disclosure> findAllFilteredWithSentiment(
            @Param("fromDate") LocalDate fromDate,
            @Param("toDate") LocalDate toDate,
            @Param("sentiment") String sentiment,
            @Param("withheld") Boolean withheld,
            @Param("q") String q,
            Pageable pageable
    );
}
