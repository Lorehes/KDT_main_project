-- V12__create_backfill_jobs.sql
-- [목적] 비동기 백필 잡 상태 추적 — 운영자가 jobId로 진행률 조회.
-- [이유] 동기 백필은 3년치 = 시간 단위 호출 → HTTP 타임아웃 위험. @Async + 진행률 DB 영속화로 해결.
-- [사이드 임팩트] 잡 행은 영구 보존(감사 용도). 정리 정책은 후속 — 별도 cleanup 잡 또는 운영자 수동.
--               status enum 미사용 — VARCHAR + CHECK로 가벼움 유지.
-- [수정 시 고려사항] 적용 후 불변. 컬럼 추가는 V{n} 새 마이그레이션.

CREATE TABLE backfill_jobs (
    id              BIGSERIAL PRIMARY KEY,
    job_id          UUID         NOT NULL UNIQUE,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    from_date       DATE         NOT NULL,
    to_date         DATE         NOT NULL,
    emit_events     BOOLEAN      NOT NULL DEFAULT false,
    chunks_total    INTEGER,                            -- 시작 시 산출, 진행 중 갱신
    chunks_done     INTEGER      NOT NULL DEFAULT 0,
    fetched         INTEGER      NOT NULL DEFAULT 0,
    saved           INTEGER      NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error_message   VARCHAR(1000),                      -- FAILED 시만
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_backfill_jobs_status_created ON backfill_jobs (status, created_at DESC);
