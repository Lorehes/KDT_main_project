package com.dartcommons.stocks.services;

import com.dartcommons.infrastructure.dart.DartCorpCodeClient;
import com.dartcommons.infrastructure.dart.DartCorpCodeClient.CorpCode;
import com.dartcommons.infrastructure.krx.KrxClient;
import com.dartcommons.infrastructure.krx.KrxClient.StockBasicInfo;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/*
 * [목적] stocks 마스터 데이터 upsert 오케스트레이터 + 캐시 위임 조회 게이트웨이.
 *       DART corpCode.xml(매핑) + KRX 종목 기본정보(시장/섹터)를 결합해 stocks 갱신.
 *       findByStockCode/findByStockCodeIn은 CacheConfig.stockByCode/stocksByCodeIn 캐시(TTL 4h) 경유.
 * [이유] 분기 동기화 잡(StockMasterSyncJob)이 호출 — 코스피200/코스닥150 리밸런싱 반영.
 *       MVP 초기 시드는 V10 마이그레이션(seed_stocks.py 산출). 본 서비스는 운영 갱신 경로.
 *       @Cacheable을 JpaRepository.findById()(기본 메서드)에 직접 붙일 수 없어 서비스 위임 패턴 채택.
 * [사이드 임팩트] **현재 KrxClient는 placeholder**(Spec 카드 #1 실측 미완료) — sector 갱신 0건.
 *               sync() 완료 시 @CacheEvict(allEntries=true) — stockByCode·stocksByCodeIn 전부 무효화.
 *               기존 행은 upsert(stockCode PK 기반) — UPDATE 발생.
 *               신규 편입 종목은 INSERT, 편입 제외 종목은 **삭제하지 않음**(portfolios FK 보호).
 *               제외 종목 정책은 후속 Spec(사용자 알림 + 마이그레이션).
 * [수정 시 고려사항] sync()는 SyncJob에서만 호출되도록 의도 — disclosure 등에서 직접 변경 금지.
 *                  대량 upsert(약 350행) — Hibernate persist는 SELECT 후 INSERT/UPDATE.
 *                  성능 이슈 시 ON CONFLICT native query 또는 saveAll 배치 고려.
 *                  findByStockCodeIn 캐시 키: TreeSet.toString() → 입력 순서 무관 안정 키. Set 이외 Collection은
 *                  TreeSet 변환에서 순서가 결정 — PK(stockCode) 기반이므로 중복 없음.
 */
@Service
@RequiredArgsConstructor
public class StockMasterService {

    private static final Logger log = LoggerFactory.getLogger(StockMasterService.class);

    private final StockRepository stockRepository;
    private final DartCorpCodeClient dartCorpCodeClient;
    private final KrxClient krxClient;

    /**
     * 단건 종목 조회 — stockByCode TTL 4h Caffeine 캐시 경유.
     * PortfolioService의 createPortfolio/getPortfolio/updatePortfolio에서 corp_name 취득 시 사용.
     */
    @Cacheable(value = "stockByCode", key = "#stockCode")
    @Transactional(readOnly = true)
    public Optional<Stock> findByStockCode(String stockCode) {
        return stockRepository.findById(stockCode);
    }

    /**
     * IN 일괄 조회 — stocksByCodeIn TTL 4h Caffeine 캐시 경유.
     * PortfolioService.listPortfolios() N+1 방지 bulk fetch 경로.
     * 키: 입력 Collection → TreeSet.toString() (정렬, 입력 순서 무관 안정 키).
     * 전제: 종목코드는 6자리 숫자(',' '[' ']' 미포함) — 키 충돌 없음. 종목코드 형식 변경 시 key 재검토 필요.
     */
    @Cacheable(value = "stocksByCodeIn",
               key = "T(java.util.TreeSet).new(#stockCodes).toString()")
    @Transactional(readOnly = true)
    public List<Stock> findByStockCodeIn(Collection<String> stockCodes) {
        return stockRepository.findByStockCodeIn(stockCodes);
    }

    /**
     * 마스터 동기화 — DART corpCode + KRX 기본정보 → 기존 stocks 행 upsert.
     * 신규 종목 추가는 V10 시드(또는 추후 별도 정책)로 분리 — 본 메서드는 *기존 행 갱신*에 집중.
     * 완료 후 stockByCode·stocksByCodeIn 캐시 전체 무효화 — stale corp_name 방지.
     *
     * @CacheEvict + @Transactional 순서: Cache 프록시가 Transaction 프록시 바깥에 위치해야
     * "트랜잭션 커밋 후 evict" 보장. Spring Boot 3.x 기본 등록 순서에서 이를 만족하지만,
     * @Order를 명시하지 않으므로 환경 변경 시 재검토 필요. 분기 1회 배치라 실위험 무시.
     *
     * @return 갱신된 행 수
     */
    @CacheEvict(value = {"stockByCode", "stocksByCodeIn"}, allEntries = true)
    @Transactional
    public int sync() {
        log.info("StockMasterService.sync() start");

        // 1. DART corp_code 매핑 — {stockCode: CorpCode}
        Map<String, CorpCode> dartMap;
        try {
            dartMap = dartCorpCodeClient.fetchAllListed().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            CorpCode::stockCode, c -> c, (a, b) -> a));
        } catch (Exception e) {
            log.error("DART corpCode fetch failed — sync 중단", e);
            return 0;
        }

        // 2. KRX 기본정보 — placeholder(0건). 실측 후 활성화
        Map<String, StockBasicInfo> krxMap;
        try {
            krxMap = krxClient.fetchAllBasicInfo();
        } catch (Exception e) {
            log.warn("KRX basic info fetch failed — corp_code만 갱신: {}", e.getMessage());
            krxMap = Map.of();
        }

        // 3. 기존 stocks 행 갱신만 — 신규 편입은 V10 시드/별도 정책 분리
        List<Stock> existing = stockRepository.findAll();
        int updated = 0;
        for (Stock stock : existing) {
            CorpCode dart = dartMap.get(stock.getStockCode());
            StockBasicInfo krx = krxMap.get(stock.getStockCode());
            if (dart == null && krx == null) continue;

            Stock.StockBuilder builder = Stock.builder()
                    .stockCode(stock.getStockCode())
                    .corpCode(dart != null ? dart.corpCode() : stock.getCorpCode())
                    .corpName(dart != null ? truncate(dart.corpName(), 100) : stock.getCorpName())
                    .market(krx != null ? krx.market() : stock.getMarket())
                    .sector(krx != null ? truncate(krx.sector(), 100) : stock.getSector());

            stockRepository.save(builder.build());
            updated++;
        }

        log.info("StockMasterService.sync() done: existing={}, updated={}, dartListed={}, krxInfo={}",
                existing.size(), updated, dartMap.size(), krxMap.size());
        return updated;
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
