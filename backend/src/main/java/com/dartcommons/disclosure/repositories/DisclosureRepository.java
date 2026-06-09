package com.dartcommons.disclosure.repositories;

import com.dartcommons.disclosure.entities.Disclosure;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/*
 * [목적] disclosures 테이블 CRUD + rcept_no 멱등 체크 + 사용자 공시 피드 조회를 제공하는 JPA 리포지토리.
 * [이유] existsByRceptNo가 중복 적재 방어의 애플리케이션 1차 게이트.
 *       DB UNIQUE 제약(V4)은 2차 게이트로 존재해 race condition도 방어.
 *       findFilteredByStocks / findAllFiltered 두 메서드로 분리: Hibernate 6은 JPQL Collection 파라미터에
 *       `:list IS NULL` 패턴을 지원하지 않아 단일 메서드로 scope=all/portfolio를 동시 처리 불가.
 *       ORDER BY는 JPQL에 명시 — Pageable Sort 자동 적용을 믿지 않고 결정론적 순서 보장(rcept_dt DESC, id DESC).
 * [사이드 임팩트] existsByRceptNo는 SELECT COUNT 쿼리 — 고빈도 폴링에서 인덱스(rcept_no UNIQUE) 의존.
 *               호출부(DisclosureQueryService)에서 stockCodes == null 여부로 메서드를 분기해야 함.
 *               Pageable은 Sort 없이(PageRequest.of(page, size)) 전달 — JPQL ORDER BY와 이중 적용 방지.
 * [수정 시 고려사항] sentiment 필터는 analysis_results JOIN이 필요해 서비스 계층에서 처리.
 *                  (stock_code, rcept_dt DESC) 복합 인덱스 추가는 performance-caching-staletime Spec 위임.
 *                  대량 배치 적재 시 saveAll + bulk insert 고려.
 */
public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    /** rcept_no가 이미 존재하는지 확인. 중복 발송 방어 1차 게이트(CLAUDE.md §4). */
    boolean existsByRceptNo(String rceptNo);

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
}
