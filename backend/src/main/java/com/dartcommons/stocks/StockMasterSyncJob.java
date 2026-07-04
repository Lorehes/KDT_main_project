package com.dartcommons.stocks;

import com.dartcommons.stocks.services.StockMasterService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/*
 * [목적] 분기 1회 stocks 마스터 데이터 동기화 잡(StockMasterService.sync 호출).
 *       리밸런싱 시점(1·4·7·10월 첫 영업일 04:00) — 거래 시간 회피.
 * [이유] feature_structure §4 "분기 1회 KRX 종목 마스터 → stocks 갱신" 요구사항.
 *       MVP 초기 시드는 V10 마이그레이션, 이후 정기 갱신은 본 잡.
 * [사이드 임팩트] 잡 자체는 sync() 위임 — 비즈니스 로직은 StockMasterService.
 *               예외 비중단 — 로깅만 하고 다음 분기에 재시도.
 *               단일 인스턴스 가정(멀티 인스턴스는 ShedLock 후속, 다른 도메인 잡과 동일 정책).
 *               BackfillRunner 같은 수동 트리거가 필요하면 service.sync()를 직접 호출 가능.
 * [수정 시 고려사항] cron 변경 시 KRX/DART rate limit 영향 검토 — 분기 1회는 충분히 여유.
 *                  KRX 실측(#1) 완료 후 sector 갱신이 실동작 — 그 전까지 sync는 corp_code 갱신만.
 *                  수동 트리거 REST 엔드포인트 추가 시 controllers/ 에 별도 클래스(인증 + 권한 필수).
 */
@Component
@RequiredArgsConstructor
public class StockMasterSyncJob {

    private static final Logger log = LoggerFactory.getLogger(StockMasterSyncJob.class);

    private final StockMasterService stockMasterService;

    /**
     * 분기 1회 — 1·4·7·10월 1일 04:00 KST.
     * 코스피200/코스닥150 리밸런싱은 분기 첫 영업일 발표 — 영업일 보정은 다음 폴링 윈도우에서 자연 흡수.
     */
    @Scheduled(cron = "0 0 4 1 1,4,7,10 *", zone = "Asia/Seoul")
    public void sync() {
        log.info("StockMasterSyncJob trigger");
        try {
            int updated = stockMasterService.sync();
            log.info("StockMasterSyncJob done: updated={}", updated);
        } catch (Exception e) {
            log.error("StockMasterSyncJob failed — will retry next quarter", e);
        }
    }
}
