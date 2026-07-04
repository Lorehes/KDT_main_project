-- V28__create_price_backfill_jobs.sql
-- [목적] KRX 과거 주가 백필 잡(krx-price-timeseries Wave B)의 상태·진행률·재개 포인트 추적.
-- [이유] 3년치 × 커버 종목 백필은 ~30분 + KRX 네트워크 불안정 → 중단 시 진행률 유실 방지.
--       V25 content / V26 embedding 백필 잡 스키마 패턴을 stocks 도메인에 적용.
--       커서가 날짜(last_processed_date)인 점만 상이 — 임베딩은 id(BIGINT), 주가 백필은 날짜 역순 반복.
-- [사이드 임팩트] PriceBackfillJob(stocks 도메인) JPA 엔티티가 본 스키마에 validate 매핑.
--               잡 행은 영구 보존(감사). targeted=백필 대상 거래일 수(시작 시 스냅샷).
-- [수정 시 고려사항] 적용 후 불변(CLAUDE.md §6-3). 컬럼 추가는 새 V{n}.
--                  status CHECK 4종(PENDING/RUNNING/SUCCEEDED/FAILED) — V13/V25/V26과 동일.
--                  last_processed_date: 역순 백필의 재개 하한(가장 오래 처리한 날짜). 재시작 시 그 이전 날짜부터.

CREATE TABLE price_backfill_jobs (
    id                    BIGSERIAL    PRIMARY KEY,
    job_id                UUID         NOT NULL UNIQUE,
    status                VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    targeted              INTEGER      NOT NULL DEFAULT 0,   -- 이번 실행 대상 평일 수
    processed             INTEGER      NOT NULL DEFAULT 0,   -- 종가 데이터 있던 날짜 수(행 수 아님)
    failed                INTEGER      NOT NULL DEFAULT 0,   -- 빈 응답(비거래일·실패) 날짜 수. 진행률=(processed+failed)/targeted
    last_processed_date   DATE,                              -- 재개 포인트(역순 처리한 가장 오래된 날짜)
    started_at            TIMESTAMPTZ,
    finished_at           TIMESTAMPTZ,
    error_message         VARCHAR(1000),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- stale RUNNING 잡 조회용 (재시작 복구 + 모니터링)
CREATE INDEX idx_price_backfill_jobs_status ON price_backfill_jobs (status, created_at DESC);
