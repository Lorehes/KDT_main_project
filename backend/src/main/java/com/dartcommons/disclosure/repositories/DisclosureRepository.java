package com.dartcommons.disclosure.repositories;

import com.dartcommons.disclosure.entities.Disclosure;
import org.springframework.data.jpa.repository.JpaRepository;

/*
 * [목적] disclosures 테이블 CRUD + rcept_no 멱등 체크를 제공하는 JPA 리포지토리.
 * [이유] existsByRceptNo가 중복 적재 방어의 애플리케이션 1차 게이트.
 *       DB UNIQUE 제약(V4)은 2차 게이트로 존재해 race condition도 방어.
 * [사이드 임팩트] existsByRceptNo는 SELECT COUNT 쿼리 — 고빈도 폴링에서 인덱스(rcept_no UNIQUE) 의존.
 * [수정 시 고려사항] 날짜/종목 기준 조회가 필요하면 idx_disclosures_corp·idx_disclosures_stock 인덱스 활용.
 *                  대량 배치 적재 시 saveAll + bulk insert 고려.
 */
public interface DisclosureRepository extends JpaRepository<Disclosure, Long> {

    /** rcept_no가 이미 존재하는지 확인. 중복 발송 방어 1차 게이트(CLAUDE.md §4). */
    boolean existsByRceptNo(String rceptNo);
}
