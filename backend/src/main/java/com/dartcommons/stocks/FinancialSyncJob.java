package com.dartcommons.stocks;

import com.dartcommons.stocks.services.FinancialSyncService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/*
 * [목적] 분기 1회 전 종목 재무 스냅샷 수집 배치 — FinancialSyncService.syncQuarter() 호출.
 *       직전 분기 사업보고서(11011) 기준. StockMasterSyncJob(cron 동일 분기 시작)과 유사 패턴.
 * [이유] analysis-stage5-financial-industry Spec R3: Stage 5 프롬프트 입력 데이터 정기 갱신.
 *       분기 배치는 폴링(1분)·KRX 일배치(18:00)보다 훨씬 낮은 빈도 → 별도 스케줄 문제없음.
 * [사이드 임팩트] 매 실행마다 이미 수집된 건은 skip(멱등) — 재실행 안전.
 *               DART API 호출량: 341종목/실행. 일 20k 쿼터의 ~1.7% 소비.
 *               throttle은 FinancialSyncService 내부(contentBackfillThrottleMs) 처리.
 * [수정 시 고려사항] cron "0 0 5 15 1,4,7,10 *": 분기 보고서 제출 마감 후(4월 중순 1Q 제출) 수집.
 *                  분기 코드(reprtCode) 자동 결정은 복잡성↑ → 사업보고서(11011) 고정으로 단순화.
 *                  반기(11012)·1Q/3Q(11013/11014) 수집이 필요하면 별도 Wave.
 */
@Component
@RequiredArgsConstructor
public class FinancialSyncJob {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncJob.class);
    private final FinancialSyncService syncService;

    /**
     * 분기 1회 — 1·4·7·10월 15일 05:00 KST (보고서 제출 마감 후 여유).
     * 사업보고서(11011) 기준으로 직전 사업연도 수집.
     */
    @Scheduled(cron = "0 0 5 15 1,4,7,10 *", zone = "Asia/Seoul")
    public void sync() {
        // 현재 기준 직전 사업연도 결정 (1~3월이면 전년도, 4월 이후면 전전년도도 포함 가능)
        // ponytail: 단순화 — 항상 직전 사업연도만 수집. 과거 누락은 시드 백필 API로 처리.
        String prevYear = String.valueOf(LocalDate.now().getYear() - 1);
        log.info("FinancialSyncJob trigger: year={}", prevYear);
        try {
            int saved = syncService.syncQuarter(prevYear, "11011");
            log.info("FinancialSyncJob done: year={} saved={}", prevYear, saved);
        } catch (Exception e) {
            log.error("FinancialSyncJob failed — will retry next quarter", e);
        }
    }
}
