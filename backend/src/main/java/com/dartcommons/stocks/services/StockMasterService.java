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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/*
 * [목적] stocks 마스터 데이터 upsert 오케스트레이터.
 *       DART corpCode.xml(매핑) + KRX 종목 기본정보(시장/섹터)를 결합해 stocks 갱신.
 * [이유] 분기 동기화 잡(StockMasterSyncJob)이 호출 — 코스피200/코스닥150 리밸런싱 반영.
 *       MVP 초기 시드는 V10 마이그레이션(seed_stocks.py 산출). 본 서비스는 운영 갱신 경로.
 * [사이드 임팩트] **현재 KrxClient는 placeholder**(Spec 카드 #1 실측 미완료) — sector 갱신 0건.
 *               DART 매핑만으로도 corp_code 변동(드물게 있음) 반영 가능.
 *               기존 행은 upsert(stockCode PK 기반) — UPDATE 발생.
 *               신규 편입 종목은 INSERT, 편입 제외 종목은 **삭제하지 않음**(portfolios FK 보호).
 *               제외 종목 정책은 후속 Spec(사용자 알림 + 마이그레이션).
 * [수정 시 고려사항] 본 서비스는 SyncJob에서만 호출되도록 의도 — disclosure 등에서 직접 변경 금지.
 *                  대량 upsert(약 350행) — Hibernate persist는 SELECT 후 INSERT/UPDATE.
 *                  성능 이슈 시 ON CONFLICT native query 또는 saveAll 배치 고려.
 *                  KRX sector가 KOSPI200/KOSDAQ150 범위 밖 종목(예: 비상장) 포함 가능 — 본 도메인 범위만 사용.
 */
@Service
@RequiredArgsConstructor
public class StockMasterService {

    private static final Logger log = LoggerFactory.getLogger(StockMasterService.class);

    private final StockRepository stockRepository;
    private final DartCorpCodeClient dartCorpCodeClient;
    private final KrxClient krxClient;

    /**
     * 마스터 동기화 — DART corpCode + KRX 기본정보 → 기존 stocks 행 upsert.
     * 신규 종목 추가는 V10 시드(또는 추후 별도 정책)로 분리 — 본 메서드는 *기존 행 갱신*에 집중.
     *
     * @return 갱신된 행 수
     */
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
