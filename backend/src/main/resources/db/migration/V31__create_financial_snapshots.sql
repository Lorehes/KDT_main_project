-- Stage 5 재무/업황 분석(analysis-stage5-financial-industry) — 단일회사 분기 주요계정 스냅샷.
-- DART fnlttSinglAcnt.json 응답 핵심 6계정(매출액·영업이익·순이익·자산·부채·자본) 저장.
-- (corp_code, bsns_year, reprt_code) UNIQUE — 멱등 UPSERT 지원.
-- analysis_results.stage_details JSONB는 이 테이블의 집계·증감을 Stage5Analyzer가 참조.
CREATE TABLE financial_snapshots (
    id              BIGSERIAL PRIMARY KEY,
    corp_code       VARCHAR(8)    NOT NULL,          -- DART 고유번호
    bsns_year       VARCHAR(4)    NOT NULL,          -- 사업연도 (예: '2024')
    reprt_code      VARCHAR(5)    NOT NULL,          -- 11011=사업보고서 11012=반기 11013=1Q 11014=3Q
    fs_div          VARCHAR(3)    NOT NULL,          -- CFS=연결 OFS=별도
    revenue         NUMERIC(22)   DEFAULT NULL,      -- 매출액 (원, 미보고 시 NULL)
    op_profit       NUMERIC(22)   DEFAULT NULL,      -- 영업이익
    net_income      NUMERIC(22)   DEFAULT NULL,      -- 당기순이익
    total_assets    NUMERIC(22)   NOT NULL,          -- 자산총계 (NULL이면 레코드 미생성)
    total_liab      NUMERIC(22)   DEFAULT NULL,      -- 부채총계
    total_equity    NUMERIC(22)   DEFAULT NULL,      -- 자본총계
    fetched_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_fin_snapshot UNIQUE (corp_code, bsns_year, reprt_code)
);

CREATE INDEX idx_fin_snap_corp ON financial_snapshots (corp_code);
CREATE INDEX idx_fin_snap_year ON financial_snapshots (bsns_year, reprt_code);
