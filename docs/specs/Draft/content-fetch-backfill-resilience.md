---
type: spec
status: Draft
created: 2026-06-29
updated: 2026-06-29
---

# content-fetch-backfill-resilience Spec

> 상태: **Draft** (dc-plan 생성)
> 원출처: dc-review-code MEDIUM-6 + P6 이슈 — disclosure-content-text-fetch Spec 구현 리뷰

## 배경 / 목적

`DisclosureContentBackfillService`가 세 가지 탄력성 문제를 가지고 있다.

1. **진행률 유실**: 12h+ 실행 중 서버 재시작 시 progress 완전 유실, 처음부터 재실행됨. DB 영속화 없음.
2. **TaskRejectedException 미처리**: `contentFetchExecutor` 큐(300) 포화 시 `DisclosureContentFetchListener`의 `@Async` 제출이 TaskRejectedException을 발생시키나 메서드 내 try-catch로 잡히지 않음(AOP 프록시 계층 발생) — 실시간 fetch 유실 위험.
3. **다중 인스턴스 CAS 무효화**: `AtomicBoolean running`은 JVM 단일 인스턴스 내에서만 유효. Kubernetes 수평 확장 시 두 인스턴스가 동시 백필 실행 가능.

영향 티어: 내부 운영 인프라 (전 티어 간접 수혜)

## 요구사항

### 요구사항 1: 진행률 DB 영속화 (가장 우선순위 높음)
- [ ] `V25__create_content_backfill_jobs.sql` 마이그레이션 — `content_backfill_jobs` 테이블 (AnalysisJob V13 패턴 참조)
  - 컬럼: `job_id UUID`, `status VARCHAR(20)`, `targeted INT`, `processed INT`, `failed INT`, `last_processed_id BIGINT`, `started_at`, `finished_at`, `error_message`, `created_at`, `updated_at`
  - `last_processed_id`: 재시작 복구 포인트 — 이 ID부터 재개 가능 (pagination Spec과 연계)
- [ ] `ContentBackfillJob` JPA 엔티티 + `ContentBackfillJobRepository` 추가
- [ ] `DisclosureContentBackfillService`에 `@Transactional(REQUIRES_NEW)`로 청크 완료마다 `last_processed_id` + 진행 카운터 DB UPDATE
- [ ] 재시작 복구: `runBackfill()` 시작 시 `status=RUNNING`인 기존 잡이 있으면 `last_processed_id`에서 재개 (중단 아닌 이어받기)
- [ ] `DisclosureContentBackfillController` GET /status에 진행률 (`processed/targeted`) 포함

### 요구사항 2: TaskRejectedException graceful 처리
- [ ] `ExecutorConfig.contentFetchExecutor()`에 custom `RejectedExecutionHandler` 등록
  - 거절 시 warn 로그 + 조용히 discard (AbortPolicy 기본값 → 커스텀 DiscardWithLog 정책)
  - 구현: `exec.setRejectedExecutionHandler((r, e) -> log.warn("contentFetchExecutor: task rejected, queue={}", e.getQueue().size()))`
- [ ] 또는 `AsyncUncaughtExceptionHandler` 구현체 등록으로 `@Async` 예외를 전역 처리 (선택적 — 이 Spec에서는 RejectedExecutionHandler 우선)
- [ ] 실시간 fetch 손실을 최소화하기 위해 `contentFetchExecutor` 큐 크기를 300 → 500으로 조정 검토 (DART 일일 한도 맥락 기재 필요)

### 요구사항 3: 다중 인스턴스 분산 락 (MVP 범위 외 — 별도 Spec으로 위임)
- [ ] **현재 MVP 단계에서는 AtomicBoolean + 단일 인스턴스 가정 유지**
- [ ] Kubernetes 수평 확장 도입 시 `net.javacrumbs.shedlock` 추가 (`@SchedulerLock`) 또는 PostgreSQL advisory lock (`SELECT pg_try_advisory_lock(?)`) 으로 교체
- [ ] 이 요구사항은 인프라 배포 전략 결정 후 별도 Spec(`content-backfill-distributed-lock`)으로 추진

## 영향 범위 (조사 결과)

- 영향 레이어: backend(disclosure, shared)
- 영향 파일:
  - `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureContentBackfillService.java`
  - `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureContentFetchListener.java`
  - `backend/src/main/java/com/dartcommons/disclosure/controllers/DisclosureContentBackfillController.java`
  - `backend/src/main/java/com/dartcommons/shared/config/ExecutorConfig.java`
  - 신규: `backend/src/main/java/com/dartcommons/disclosure/entities/ContentBackfillJob.java`
  - 신규: `backend/src/main/java/com/dartcommons/disclosure/repositories/ContentBackfillJobRepository.java`
- DB 변경: `V25__create_content_backfill_jobs.sql` 신규 테이블 (Flyway 마이그레이션 필요)
- 외부 계약 변경: 없음

## 관련 패턴 / 과거 사례

- `backend/src/main/java/com/dartcommons/analysis/entities/AnalysisJob.java` — job_id, status, targeted/analyzed/failed, chunks_done, started_at/finished_at, errorMessage 패턴 (직접 재사용)
- `backend/src/main/resources/db/migration/V13__create_analysis_jobs.sql` — 잡 추적 테이블 DDL 패턴
- `backend/src/main/java/com/dartcommons/analysis/services/AnalysisBackfillService.java` — `@Transactional(REQUIRES_NEW)`로 청크 진행률 독립 커밋 패턴
- 분산 락 참조 주석: `DisclosurePollingJob.java`, `NotificationRetryJob.java` (ShedLock 언급, 미구현)

## 리스크 / 법적 검토

- **재시작 복구 정확성**: `last_processed_id` 기반 재개 시 커서 기반 pagination(content-fetch-backfill-pagination Spec)이 선행 구현되어야 함. 이 두 Spec은 의존관계 있음.
- **REQUIRES_NEW 트랜잭션 오버헤드**: 청크 완료마다 별도 커밋 — 100건 청크면 OK, 1건 단위면 과부하. 청크 크기는 pagination Spec의 `contentBackfillChunkSize` 설정값 사용.
- **content_backfill_jobs 행 누적**: 잡 행 영구 보존(감사). 정리 정책은 별도 cleanup 잡 후속.
- 법적 제약: 해당 없음 (운영 인프라)

## 권장 구현 방향

**우선순위**: 요구사항 1(진행률 영속화) → 요구사항 2(RejectedExecutionHandler) → 요구사항 3(MVP 이후)

**content-fetch-backfill-pagination Spec과 의존관계**:
- `last_processed_id` 재개 기능은 pagination Spec의 커서 기반 쿼리(`findPendingContentFetchIds(lastId, pageable)`)를 전제로 함
- 두 Spec을 동일 wave에서 구현하거나 pagination 먼저 구현 후 resilience 구현 권장

**`content_backfill_jobs` 테이블 설계 (V13 참조):**

```sql
CREATE TABLE content_backfill_jobs (
    id                  BIGSERIAL    PRIMARY KEY,
    job_id              UUID         NOT NULL UNIQUE,
    status              VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    targeted            INTEGER      NOT NULL DEFAULT 0,
    processed           INTEGER      NOT NULL DEFAULT 0,
    failed              INTEGER      NOT NULL DEFAULT 0,
    last_processed_id   BIGINT,           -- 재시작 복구 포인트
    started_at          TIMESTAMPTZ,
    finished_at         TIMESTAMPTZ,
    error_message       VARCHAR(1000),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_content_backfill_jobs_status ON content_backfill_jobs (status, created_at DESC);
```

**RejectedExecutionHandler 등록 (contentFetchExecutor):**

```java
exec.setRejectedExecutionHandler((runnable, executor) ->
    log.warn("contentFetchExecutor: task rejected — queue full (size={}), dropping task",
        executor.getQueue().size())
);
```

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
