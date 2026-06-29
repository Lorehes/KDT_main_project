---
type: spec
status: Approved
created: 2026-06-29
updated: 2026-06-29
---

# content-fetch-backfill-resilience Spec

> 상태: Draft → **Approved** (2026-06-29, dc-tech-review 승인)
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

## Tech Review (dc-tech-review · 2026-06-29)

> 선행 의존 `content-fetch-backfill-pagination` 구현 완료(SHA fb82e31, 2026-06-29) — `last_processed_id` 재개의 전제인 커서 쿼리 `findPendingContentFetchIds(lastId, Pageable)`가 이미 존재. 본 Spec 즉시 착수 가능.

### 아키텍처 분해

- **영향 레이어**: backend(disclosure, shared) — 외부 계약/FE/타 도메인 영향 없음.
- **핵심 전환**: `DisclosureContentBackfillService`를 **AtomicBoolean 단일 플래그 → 잡(Job) 추적 모델**로 리팩터. `analysis/AnalysisJob` + `AnalysisBackfillService`가 **검증된 직접 선례** — 동일 구조(엔티티 상태머신 + `REQUIRES_NEW` 청크 진행률 + 컨트롤러 createJob→runAsync→getJob)를 disclosure 도메인에 복제.
- **신규**: `ContentBackfillJob`(엔티티), `ContentBackfillJobRepository`, `V25__create_content_backfill_jobs.sql`.
- **수정**: `DisclosureContentBackfillService`(잡 기반 재작성), `DisclosureContentBackfillController`(잡 API 전환), `ExecutorConfig`(RejectedExecutionHandler).
- **AtomicBoolean 존치 결정**: 요구사항 3(분산 락)을 MVP 범위 외로 두므로 `running` AtomicBoolean은 **JVM 내 빠른 중복 차단용으로 유지**. 잡 테이블은 진행률 영속·재개를 담당(역할 분리). 재시작 후 `running=false`(새 JVM)인데 DB에 `status=RUNNING` 잡이 있으면 → **stale(크래시) 잡으로 판정 후 `last_processed_id`부터 이어받기**. 이것이 단일 인스턴스 MVP의 재개 규칙.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `V25__create_content_backfill_jobs.sql` 마이그레이션 (Spec §권장 DDL + V13 패턴) | backend/disclosure(db) | BE | 하 | - |
| 2 | `ContentBackfillJob` 엔티티 + `ContentBackfillJobRepository`(`findByJobId`, `findFirstByStatusOrderByCreatedAtDesc`) | backend/disclosure | BE | 하 | #1 |
| 3 | `DisclosureContentBackfillService` 잡 기반 재작성 — `createJob()` / `@Async runAsync(jobId)` / `REQUIRES_NEW` 청크 진행률(`last_processed_id`+카운터) / stale RUNNING 잡 재개 / AtomicBoolean 존치 | backend/disclosure | BE | 상 | #2 |
| 4 | `DisclosureContentBackfillController` 전환 — `POST` createJob+runAsync(202), `GET /jobs/{jobId}` 진행률(`processed/targeted`) | backend/disclosure | BE | 중 | #3 |
| 5 | `ExecutorConfig.contentFetchExecutor()` custom `RejectedExecutionHandler`(warn+discard) 등록 (요구사항 2) | backend/shared | BE | 하 | - |
| 6 | 통합 테스트(Testcontainers) — 청크 진행률 영속 + stale RUNNING 잡 재개(`last_processed_id`부터) 검증 | backend/disclosure(test) | BE | 중 | #3,#4 |

> 카드 #5는 #1~#4와 독립 — 병렬 가능. 요구사항 3(분산 락)은 카드화하지 않음(아래 범위 외).

### DB / 마이그레이션 영향

- **신규**: `backend/src/main/resources/db/migration/V25__create_content_backfill_jobs.sql` (V24가 현재 최신 — V25 올바름). Spec §권장 DDL 그대로 사용 + V13 헤더 주석 컨벤션 준수.
- **인덱스**: `idx_content_backfill_jobs_status (status, created_at DESC)` — stale RUNNING 잡 조회용.
- **`db_schema.md` SSOT**: 현재 §3에 `analysis_jobs`(V13)·`backfill_jobs`(V12) **잡 추적 테이블이 등재돼 있지 않음** — 잡 테이블은 코어 도메인 스키마에서 제외하는 기존 관례. `content_backfill_jobs`도 이 선례를 따라 §3 등재 생략이 일관적. (등재를 원하면 별도 결정 — **확인 필요**, 선택 사항.)
- **ddl-auto: validate** 유지 — 엔티티-스키마 정합 필수(컬럼명/타입 V13 패턴 대조).

### 외부 계약 영향

- 없음. DART/KRX/카카오/LLM 프롬프트·응답 파싱 무변경. 관리자 엔드포인트 응답 형태만 `{started, running}` → `JobResponse`(jobId/status/processed/targeted/...)로 변경 — 내부 운영 API라 외부 소비자 없음.

### 리스크 & 법적 검토

- **stale RUNNING 잡 판정 정확성(기술)**: 단일 인스턴스 가정에서 `running=false ∧ DB status=RUNNING` = 크래시 잡. **다중 인스턴스 도입 시 이 가정이 깨짐** — 동시 실행 인스턴스의 정상 RUNNING 잡을 stale로 오인 가능. 요구사항 3(분산 락)이 이 갭을 메우는 후속이며, 그 전까지는 **단일 인스턴스 배포 불변식**을 운영 문서에 명기해야 함(코드 주석 [수정 시 고려사항]에 강제).
- **`last_processed_id` ↔ pagination 커서 정합(기술)**: 재개 lastId는 pagination의 `d.id > :lastId AND ORDER BY id ASC`에 그대로 투입 — id ASC 정렬 일치로 정확. 단, 재개 시 이미 fetch된 행은 `content_fetched_at IS NULL` 필터로 자동 제외되므로 `last_processed_id`는 **하한 힌트**일 뿐 중복 fetch를 유발하지 않음(이중 안전).
- **REQUIRES_NEW 트랜잭션 오버헤드(기술)**: 청크(기본 100건) 완료마다 1 UPDATE — AnalysisBackfillService와 동일 수준, 무해. 1건 단위 커밋 금지(청크 경계에서만).
- **TaskRejectedException 처리 범위(기술)**: 리스너 내부 try-catch는 `@Async` **실행 중** 예외만 포착 — 제출 거절(큐 포화)은 AOP 프록시 계층에서 발생해 미포착. 따라서 카드 #5의 executor 레벨 `RejectedExecutionHandler`가 정확한 해법(Spec 진단 타당).
- **잡 행 누적(운영)**: 영구 보존(감사). cleanup 정책은 별도 후속(analysis_jobs와 동일하게 미정 — 부채 공유).
- **법적 제약**: 해당 없음(운영 인프라, 개인정보·자본시장법 무관).

### 범위 외 (명시적 deferral)

- **요구사항 3 — 다중 인스턴스 분산 락**: MVP 단일 인스턴스 가정 하에 **이번 Spec에서 구현하지 않음**. Kubernetes 수평 확장 결정 후 별도 Spec `content-backfill-distributed-lock`(ShedLock `@SchedulerLock` 또는 `pg_try_advisory_lock`)로 추진. 본 Spec은 그 진입점(잡 테이블·재개 로직)까지만 마련.

### 예상 wave 수

- **1 wave** (카드 #1~#6). 카드 #5는 독립이라 동일 wave 내 병렬. 요구사항 3은 별도 미래 Spec으로 분리.
