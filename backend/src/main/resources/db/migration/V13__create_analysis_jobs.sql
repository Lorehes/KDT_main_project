-- V13__create_analysis_jobs.sql
-- [목적] LLM 분석(Stage 2~5) 배치 백필 잡의 상태/진행률 추적 — 운영자가 jobId로 조회.
-- [이유] disclosure backfill_jobs(V12)는 from_date/to_date 날짜 범위 의미라 시맨틱 불일치.
--       analysis 백필은 "미분석 공시 id 범위 + 단계" 의미라 별도 테이블 분리.
-- [사이드 임팩트] analysis 도메인의 AnalysisJob 엔티티(JPA validate)가 본 스키마에 매핑.
--                  잡 행은 영구 보존(감사). 정리는 별도 운영 cleanup.
-- [수정 시 고려사항] 적용 후 불변(CLAUDE.md §6-3). 컬럼 추가는 새 V{n}.
--                  stage CHECK(2~5)는 본 Spec=2, Stage 3~5는 후속 Spec에서 재사용.

CREATE TABLE analysis_jobs (
    id                  BIGSERIAL    PRIMARY KEY,
    job_id              UUID         NOT NULL UNIQUE,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    stage               SMALLINT     NOT NULL CHECK (stage BETWEEN 2 AND 5),
    -- 범위 nullable: NULL이면 "전체 미분석 공시" 의미. 둘 다 NULL이면 무제한.
    disclosure_id_from  BIGINT,
    disclosure_id_to    BIGINT,
    chunk_size          INTEGER      NOT NULL DEFAULT 100,
    chunks_total        INTEGER,                            -- 시작 시 산출
    chunks_done         INTEGER      NOT NULL DEFAULT 0,
    targeted            INTEGER      NOT NULL DEFAULT 0,    -- 대상 공시 수
    analyzed            INTEGER      NOT NULL DEFAULT 0,    -- 성공 분석 수
    failed              INTEGER      NOT NULL DEFAULT 0,    -- 분석 실패(파싱/타임아웃 등)
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_analysis_jobs_status_created ON analysis_jobs (status, created_at DESC);
