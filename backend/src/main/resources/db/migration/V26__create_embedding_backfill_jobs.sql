-- V26__create_embedding_backfill_jobs.sql
-- [목적] Stage 3 RAG 임베딩 백필 잡의 상태/진행률/재개 포인트 추적 — embedding_backfill_jobs 테이블 생성.
-- [이유] EmbeddingBackfillService가 AtomicBoolean 단일 플래그만 사용하면 재시작 시 진행률 유실.
--       V25 content_backfill_jobs 스키마 패턴을 analysis 도메인에 적용 — last_processed_id가 커서 재개 포인트.
-- [사이드 임팩트] EmbeddingBackfillJob JPA 엔티티(analysis 도메인)가 본 스키마에 validate 매핑됨.
--               잡 행은 영구 보존(감사). 정리는 별도 cleanup 잡 후속.
-- [수정 시 고려사항] 적용 후 불변(CLAUDE.md §6-3). 컬럼 추가는 새 V{n} 마이그레이션.
--                  status CHECK는 V13/V25와 동일 4종(PENDING/RUNNING/SUCCEEDED/FAILED).
--                  last_processed_id: 임베딩 백필 커서(id ASC)의 재개 하한.

CREATE TABLE embedding_backfill_jobs (
    id                  BIGSERIAL    PRIMARY KEY,
    job_id              UUID         NOT NULL UNIQUE,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    targeted            INTEGER      NOT NULL DEFAULT 0,
    processed           INTEGER      NOT NULL DEFAULT 0,
    failed              INTEGER      NOT NULL DEFAULT 0,
    last_processed_id   BIGINT,                          -- 재시작 복구 포인트 (커서 재개 하한)
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- stale RUNNING 잡 조회용 (재시작 복구 + 모니터링)
CREATE INDEX idx_embedding_backfill_jobs_status ON embedding_backfill_jobs (status, created_at DESC);
