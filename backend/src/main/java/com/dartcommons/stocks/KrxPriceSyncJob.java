package com.dartcommons.stocks;

import com.dartcommons.infrastructure.krx.KrxClient;
import com.dartcommons.infrastructure.krx.KrxClient.StockCloseInfo;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/*
 * [목적] 평일 18:00 KST 일배치 — KRX 전종목 종가를 stocks.close_price/price_asof에 적재.
 *       종가 적재 완료 후 stockByCode·stocksByCodeIn Caffeine 캐시를 전체 무효화.
 * [이유] 평일 장 마감(15:30) + KRX 데이터 확정(~16:30) 후 여유를 두고 18:00에 실행.
 *       KrxClient.fetchAllClosePrices()는 KRX 직접(MDCSTAT01501) → GitHub cache CSV 폴백 2단계.
 *       캐시 evict 없이는 TTL(4h) 만료 전까지 stale 종가가 평가 손익 집계에 반영될 수 있음.
 * [사이드 임팩트] JPA dirty-checking으로 변경 종목만 UPDATE 발생(SELECT 후 비교).
 *               빈 Map 반환 시(KRX·GitHub 모두 실패) UPDATE 0건 — 이전 종가 유지, 캐시는 그래도 evict.
 *               @CacheEvict + @Transactional 프록시 순서 이슈: StockMasterService.sync()와 동일 패턴.
 *               Spring Boot 3.x 기본 프록시 순서에서 캐시 evict는 트랜잭션 커밋 후 발생 — 분기 1회 배치라 실위험 무시.
 * [수정 시 고려사항] Stage 5(주가 5일 반응) 착수 시 stock_prices 시계열 테이블 도입 — 이 잡의 역할은
 *                  stocks.close_price 갱신(빠른 요약용)과 stock_prices INSERT(시계열용) 병행으로 확장.
 *                  비거래일(공휴일·주말) 에러 없이 종료 — fetchAllClosePrices()가 빈 Map 반환하면 UPDATE 0건.
 *                  배치 실패 알림이 필요하면 ApplicationEventPublisher로 NotificationDispatcher에 이벤트 발행.
 */
@Component
@RequiredArgsConstructor
public class KrxPriceSyncJob {

    private static final Logger log = LoggerFactory.getLogger(KrxPriceSyncJob.class);

    private final KrxClient       krxClient;
    private final StockRepository stockRepository;

    /**
     * 평일 18:00 KST — 종가 일배치 적재.
     * KrxClient 2단계 폴백 완료 후 stocks 전체를 스캔해 dirty-checking으로 UPDATE.
     * 완료 후 stockByCode·stocksByCodeIn 캐시 전체 무효화 — stale 종가 방지(카드 #7).
     */
    @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Seoul")
    @Transactional
    @CacheEvict(value = {"stockByCode", "stocksByCodeIn"}, allEntries = true)
    public void syncPrices() {
        log.info("KrxPriceSyncJob 시작");

        Map<String, StockCloseInfo> priceMap = krxClient.fetchAllClosePrices();
        if (priceMap.isEmpty()) {
            log.warn("KrxPriceSyncJob: 종가 데이터 없음(KRX·GitHub 모두 실패) — 이번 배치 스킵");
            return;
        }

        List<Stock> stocks = stockRepository.findAll();
        int updated = 0;
        for (Stock stock : stocks) {
            StockCloseInfo info = priceMap.get(stock.getStockCode());
            if (info == null) continue;
            stock.updatePrice(info.closePrice(), info.priceAsof());
            updated++;
        }

        log.info("KrxPriceSyncJob 완료: 종가수집={}, DB업데이트={}", priceMap.size(), updated);
    }
}
