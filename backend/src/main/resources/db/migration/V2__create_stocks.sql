-- V2__create_stocks.sql
-- [목적] 종목 마스터(보조) — DART 고유번호(corp_code) ↔ KRX 종목코드(stock_code) 정규화 (db_schema.md 3.2).
-- [이유] portfolios·disclosures가 공통 참조하는 정규화 기준 테이블.
-- [사이드 임팩트] V3 portfolios·V4 disclosures가 stock_code를 FK로 참조.
-- [수정 시 고려사항] KRX 종목 마스터 분기 배치(StockMasterSyncJob)로 갱신. 적용 후 불변.

CREATE TABLE stocks (
    stock_code  VARCHAR(6)   PRIMARY KEY,              -- KRX 종목코드(6자리)
    corp_code   VARCHAR(8)   NOT NULL UNIQUE,          -- DART 고유번호(8자리)
    corp_name   VARCHAR(100) NOT NULL,
    market      VARCHAR(10)  CHECK (market IN ('KOSPI','KOSDAQ','KONEX')),
    sector      VARCHAR(100),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_stocks_corp_name ON stocks (corp_name);
