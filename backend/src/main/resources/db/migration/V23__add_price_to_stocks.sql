-- V23: stocks 테이블에 현재가(일별 종가) 컬럼 추가 (dashboard-eval-pnl Spec, 접근법 A MVP)
-- V21·V22는 기존 사용 중.
-- close_price / price_asof 모두 NULL 허용 — 배치 미실행/비거래일에도 마스터 행 유지.
-- ddl-auto: validate → Stock.java 엔티티와 동시 갱신 필수(컬럼 불일치 시 부팅 실패).

ALTER TABLE stocks
    ADD COLUMN close_price NUMERIC(20, 4),
    ADD COLUMN price_asof  DATE;

COMMENT ON COLUMN stocks.close_price IS '최신 종가(KRX MDCSTAT01501 일별시세 배치 적재). NULL=미수집.';
COMMENT ON COLUMN stocks.price_asof  IS '종가 기준 거래일(KST). NULL=미수집.';
