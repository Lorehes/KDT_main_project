package com.dartcommons.stocks.services;

import com.dartcommons.infrastructure.dart.DartFinancialClient;
import com.dartcommons.infrastructure.dart.DartFinancialClient.FinancialSnapshot;
import com.dartcommons.infrastructure.dart.DartApiProperties;
import com.dartcommons.stocks.entities.FinancialSnapshot.FinancialSnapshotBuilder;
import com.dartcommons.stocks.repositories.FinancialSnapshotRepository;
import com.dartcommons.stocks.repositories.StockRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;

import java.util.ArrayList;
import java.util.List;

/*
 * [목적] 전 종목(코스피200+코스닥150)의 분기 재무 스냅샷을 DART에서 수집해 financial_snapshots에 저장.
 *       분기 1회 배치(FinancialSyncJob) + 관리자 시드 백필 양쪽에서 호출된다.
 * [이유] analysis-stage5-financial-industry Spec R3/R4: Stage 5 프롬프트 입력 데이터 사전 적재.
 *       DART 일 20k 쿼터 보호 — contentBackfillThrottleMs(기본 500ms) throttle 재사용.
 *       이미 수집된 (corp_code, bsns_year, reprt_code) 는 skip(멱등). 실패 건 skip, 계속 진행.
 * [사이드 임팩트] 341종목 × 1분기 = 341 DART 콜. 8분기 시드 백필 = ~2,728 DART 콜.
 *               DART API 실패(status 020/800/900)는 @Retryable 재시도 → 그래도 실패 시 로그 후 skip.
 *               금융업 계정체계 상이로 total_assets 매칭 실패 시 Optional.empty → skip.
 * [수정 시 고려사항] throttleMs 환경변수(CONTENT_BACKFILL_THROTTLE_MS) 재사용 — 재무 수집 전용 설정이 필요하면 별도 properties 추가.
 *                  reprtCode 우선순위: 분기 배치는 최근 보고서 코드(매 분기 결정), 시드 백필은 사업보고서(11011) 고정.
 */
@Service
public class FinancialSyncService {

    private static final Logger log = LoggerFactory.getLogger(FinancialSyncService.class);

    private final DartFinancialClient dartFinancialClient;
    private final FinancialSnapshotRepository snapshotRepo;
    private final StockRepository stockRepo;
    private final DartApiProperties dartProps;

    public FinancialSyncService(DartFinancialClient dartFinancialClient,
                                FinancialSnapshotRepository snapshotRepo,
                                StockRepository stockRepo,
                                DartApiProperties dartProps) {
        this.dartFinancialClient = dartFinancialClient;
        this.snapshotRepo = snapshotRepo;
        this.stockRepo = stockRepo;
        this.dartProps = dartProps;
    }

    /**
     * 단일 분기 전 종목 수집 — 이미 있으면 skip(멱등).
     *
     * @param bsnsYear  사업연도 (예: "2024")
     * @param reprtCode 보고서 코드 (11011=사업보고서 11012=반기 11013=1Q 11014=3Q)
     * @return 저장(신규) 건수
     */
    public int syncQuarter(String bsnsYear, String reprtCode) {
        List<String> corpCodes = getCorpCodes();
        int saved = 0;

        for (String corpCode : corpCodes) {
            if (snapshotRepo.existsByCorpCodeAndBsnsYearAndReprtCode(corpCode, bsnsYear, reprtCode)) {
                log.debug("FinancialSyncService: skip(already) corpCode={} year={} reprt={}", corpCode, bsnsYear, reprtCode);
                continue; // DART 미호출이므로 throttle 불필요
            }
            try {
                var snapshot = dartFinancialClient.fetchSnapshot(corpCode, bsnsYear, reprtCode);
                snapshot.ifPresent(s -> saveSnapshot(s));
                if (snapshot.isPresent()) saved++;
            } catch (RestClientException e) {
                log.warn("FinancialSyncService: DART 호출 실패 corpCode={} year={} reprt={} err={}", corpCode, bsnsYear, reprtCode, e.getMessage());
            }
            throttle();
        }
        log.info("FinancialSyncService.syncQuarter done: year={} reprt={} saved={}/{}", bsnsYear, reprtCode, saved, corpCodes.size());
        return saved;
    }

    /**
     * 시드 백필 — 최근 N개 분기(사업보고서 11011만)를 지정 연도부터 소급 수집.
     * Spec 결정 4: 8분기(2년) 기준.
     *
     * @param years 소급할 사업연도 목록 (예: ["2024","2023","2022","2021",...])
     * @return 저장 건수
     */
    public int seedBackfill(List<String> years) {
        int total = 0;
        for (String year : years) {
            total += syncQuarter(year, "11011");
        }
        log.info("FinancialSyncService.seedBackfill done: years={} totalSaved={}", years, total);
        return total;
    }

    @Transactional
    public void saveSnapshot(FinancialSnapshot s) {
        com.dartcommons.stocks.entities.FinancialSnapshot entity =
                com.dartcommons.stocks.entities.FinancialSnapshot.builder()
                        .corpCode(s.corpCode())
                        .bsnsYear(s.bsnsYear())
                        .reprtCode(s.reprtCode())
                        .fsDiv(s.fsDiv())
                        .revenue(s.revenue())
                        .opProfit(s.opProfit())
                        .netIncome(s.netIncome())
                        .totalAssets(s.totalAssets())
                        .totalLiab(s.totalLiab())
                        .totalEquity(s.totalEquity())
                        .build();
        snapshotRepo.save(entity);
    }

    private List<String> getCorpCodes() {
        // scalar 쿼리로 corp_code만 조회 — findAll()로 엔티티 풀로드 불필요
        return stockRepo.findAllCorpCodes();
    }

    private void throttle() {
        long ms = dartProps.contentBackfillThrottleMs();
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
