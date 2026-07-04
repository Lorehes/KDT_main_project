-- V27__create_stock_prices.sql
-- [목적] KRX 일별 종가 시계열 테이블 신설 — stocks.close_price(최신 1건 요약)를 보완하는 접근법 B.
--       공시 후 D+1~D+5 주가 반응 산출(disclosure-detail-redesign 예측 차트 #8/#9)을 위한 데이터 계층.
-- [이유] stocks.close_price는 최신 종가 1개만 보유해 일자별 반응 계산 불가.
--       krx-price-timeseries Spec 확정 결정: 커버 종목 한정(코스피200+코스닥150 ~341종), 3년 백필,
--       raw 종가(수정주가 무보정, ±50% 이상치 방어), ON CONFLICT DO NOTHING 멱등.
-- [사이드 임팩트] KrxPriceSyncJob이 당일 종가를 stocks + stock_prices 병행 적재.
--               PriceBackfillService(Wave B)가 3년 역순 백필.
--               StockPriceProvider.findReactionSeries(Wave C)가 반응 산출에 사용.
-- [수정 시 고려사항] 적용 후 불변(CLAUDE.md §6-3). 컬럼 추가는 새 V{n} 마이그레이션.
--                  수정주가 보정 도입 시 adjusted_close_price 컬럼을 별도 V{n}으로 추가.
--                  전종목 확대 시 인덱스 추가 검토(현재 PK 단독으로 커버 종목 반응 조회 커버).

CREATE TABLE stock_prices (
    stock_code  VARCHAR(6)    NOT NULL REFERENCES stocks (stock_code),
    trade_date  DATE          NOT NULL,
    close_price NUMERIC(20,4) NOT NULL,              -- stocks.close_price와 동일 정밀도(V23). 공개 시세라 평문.
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (stock_code, trade_date)             -- 멱등 upsert 키 + D+N 범위 조회 인덱스 겸용
);

-- (stock_code, trade_date) PK가 이미 반응 조회 패턴을 커버:
--   WHERE stock_code = ? AND trade_date >= D0 ORDER BY trade_date LIMIT 6
-- 별도 인덱스 불필요 (커버 종목 ~341 × 3년 ≈ 26만 행 규모).
