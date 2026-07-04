-- V29__add_partial_status_to_price_backfill_jobs.sql
-- [목적] price_backfill_jobs.status CHECK 제약에 'PARTIAL' 추가 — 가용 이력 경계 도달 시 부분 완료 표기.
-- [이유] V28의 인라인 CHECK(4종)에 PARTIAL을 넣으려면 제약 교체 필요(PostgreSQL은 ADD COLUMN 없이 CHECK 수정 불가).
--       PriceBackfillJob.Status enum에 PARTIAL 추가(Java 코드)와 동기화(V28 불변).
-- [사이드 임팩트] 기존 FAILED/SUCCEEDED 행은 새 CHECK에도 유효 → 데이터 무손실. 컬럼/인덱스 무변경.
-- [수정 시 고려사항] V28의 인라인 CHECK 자동명 = price_backfill_jobs_status_check(PostgreSQL 규칙).
--                  신규 상태 추가 시 또 새 V{n}으로 교체. 적용된 V29는 불변.
ALTER TABLE price_backfill_jobs
    DROP CONSTRAINT IF EXISTS price_backfill_jobs_status_check,
    ADD CONSTRAINT price_backfill_jobs_status_check
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'PARTIAL'));
