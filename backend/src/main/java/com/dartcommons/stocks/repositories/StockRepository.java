package com.dartcommons.stocks.repositories;

import com.dartcommons.stocks.entities.Stock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;

/*
 * [목적] stocks 마스터 CRUD + 커버 종목 코드 일괄 조회를 제공하는 JPA 리포지토리.
 * [이유] findAllStockCodes()는 disclosure 커버 필터의 N+1 쿼리(deferred HIGH) 해결용 —
 *       배치당 1회 호출해 Set 메모리 캐시로 사용. 약 350행이라 메모리 부담 무시 가능.
 * [사이드 임팩트] findAllStockCodes()를 Caffeine 캐시(@Cacheable("stocks"))로 감싸면
 *               분기 동기화 잡(StockMasterSyncJob)에서 evict 필요. 본 Spec 범위 외(후속).
 * [수정 시 고려사항] existsByStockCode는 인덱스(PK) 직격 — 단건 조회 시 사용.
 *                  대량 upsert는 @Modifying + native query(ON CONFLICT) 권장 — Hibernate persist는 SELECT 후 INSERT.
 *                  market/sector 필터 조회가 필요하면 findByMarket 등 메서드 추가.
 */
public interface StockRepository extends JpaRepository<Stock, String> {

    /**
     * 커버 종목 코드 전체를 Set으로 반환.
     * DisclosureCollectionService.collect()가 배치 진입 시 1회 호출 → N+1 회피.
     */
    @Query("SELECT s.stockCode FROM Stock s")
    Set<String> findAllStockCodes();

    /** 단건 멱등 체크 — 커버 종목 추가 등 단발 조회용. */
    boolean existsByStockCode(String stockCode);
}
