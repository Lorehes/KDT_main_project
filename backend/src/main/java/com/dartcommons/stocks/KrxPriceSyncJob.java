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

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *               2단 이상치 방어: KrxClient(1단, 절대 — 1원 미만) + syncPrices(2단, 상대 — 전일 대비 ±50%).
 *               액면분할·합병 시 ±50% 초과가 정상일 수 있음 — WARN 로그만 남기고 배치 실패로 처리 안 함(graceful).
 * [수정 시 고려사항] Stage 5(주가 5일 반응) 착수 시 stock_prices 시계열 테이블 도입 — 이 잡의 역할은
 *                  stocks.close_price 갱신(빠른 요약용)과 stock_prices INSERT(시계열용) 병행으로 확장.
 *                  비거래일(공휴일·주말) 에러 없이 종료 — fetchAllClosePrices()가 빈 Map 반환하면 UPDATE 0건.
 *                  배치 실패 알림이 필요하면 ApplicationEventPublisher로 NotificationDispatcher에 이벤트 발행.
 *                  ±50% 임계는 상수화 권장 — 향후 조정 필요 시 한 곳만 수정.
 */
@Component
@RequiredArgsConstructor
public class KrxPriceSyncJob {

    private static final Logger log = LoggerFactory.getLogger(KrxPriceSyncJob.class);

    private final KrxClient       krxClient;
    private final StockRepository stockRepository;

    /** 전일 대비 변동률 ±50% 초과를 이상치로 판단하는 임계 — 상/하한가(±30%)는 통과, 명백한 오염만 차단. */
    private static final BigDecimal ANOMALY_THRESHOLD = new BigDecimal("0.5");

    /**
     * 평일 18:00 KST — 종가 일배치 적재.
     * KrxClient 2단계 폴백(1단: 절대 이상치) 완료 후 stocks 전체 스캔 + dirty-checking UPDATE.
     * 2단 이상치(전일 대비 ±50% 초과)는 WARN 로그 후 해당 종목 스킵 — findAll() 로드 비용 무추가.
     * 완료 후 stockByCode·stocksByCodeIn 캐시 전체 무효화 — stale 종가 방지.
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
        int anomalySkipped = 0;
        for (Stock stock : stocks) {
            StockCloseInfo info = priceMap.get(stock.getStockCode());
            if (info == null) continue;

            // 2단 이상치 방어: 전일 종가가 있을 때만 비교 — 첫 적재(null)는 항상 허용
            BigDecimal prevPrice = stock.getClosePrice();
            if (prevPrice != null && prevPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal changeRate = info.closePrice().subtract(prevPrice)
                        .divide(prevPrice, 4, RoundingMode.HALF_UP)
                        .abs();
                if (changeRate.compareTo(ANOMALY_THRESHOLD) > 0) {
                    log.warn("KrxPriceSyncJob 이상치 스킵 — stockCode={}, 전일={}, 신규={}, 변동={}%",
                            stock.getStockCode(), prevPrice, info.closePrice(),
                            changeRate.multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP));
                    anomalySkipped++;
                    continue;
                }
            }

            stock.updatePrice(info.closePrice(), info.priceAsof());
            updated++;
        }

        if (anomalySkipped > 0) {
            log.warn("KrxPriceSyncJob: 이상치(±50% 초과) 스킵 {}건 — 액면분할·합병이면 정상, 그 외 수동 검토 권장",
                    anomalySkipped);
        }
        log.info("KrxPriceSyncJob 완료: 종가수집={}, DB업데이트={}, 이상치스킵={}",
                priceMap.size(), updated, anomalySkipped);
    }
}
