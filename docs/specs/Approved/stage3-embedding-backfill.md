---
type: spec
status: Approved
created: 2026-07-01
updated: 2026-07-01
---

# Stage 3 임베딩 백필 Spec

> 상태: **Approved** (2026-07-01, dc-tech-review 승인)

## 배경 / 목적

Stage 3 RAG는 구현·설정·컬렉션이 모두 준비됐고 파이프도 켜져 있으나(`CHROMA_ENABLED=true`, `EMBEDDING_PROVIDER=ollama`), **`disclosure_embeddings` 컬렉션에 벡터가 0건**이다. `Stage3RagService.upsert()`는 공시를 분석할 때 그 건만 embed-upsert하는 증분 방식이라, 이미 적재된 과거 코퍼스는 하나도 Chroma에 들어가 있지 않다. 결과적으로 Stage 3의 `findSimilar()`는 항상 빈 결과를 낸다(유사 공시 검색 실질 무동작).

이 Spec은 `content_text` 보유 공시 전건을 일괄 임베딩해 Chroma에 적재하는 **관리자용 백필 잡**을 추가하고, 그 과정에서 발견된 **긴 문서 임베딩 500 버그**를 증분 경로와 공유되는 단일 지점에서 함께 해결한다.

- **티어**: 운영 인프라(관리자 전용). Stage 3 RAG는 Pro+ 응답에 `similar_disclosures` 제공(통합기획서 §3.3) — 본 백필이 그 데이터 소스를 채운다.
- **페르소나**: 간접(전 사용자) — RAG 유사 공시 품질이 분석 신뢰도에 기여.

### 실측 근거 (2026-07-01)

- Chroma `disclosure_embeddings` 컬렉션 `count=0` (컨테이너 healthy, 포트 8001, cosine).
- `content_text IS NOT NULL` 공시 **67,847건** (min 121자 / median 2,015자 / avg 12,627자 / max 50,000자).
- 임베딩 소요: 633자 39ms · 2,156자 73ms · 3,000자 125ms · 6,000자 259ms (nomic-embed-text, 768차원).
- **실패 임계 ≈6,700자**: 초과 시 Ollama가 **자르지 않고 HTTP 500** 반환 — `{"error":"the input length exceeds the context length"}` (2048 토큰 컨텍스트).
- **6,000자 초과 21,439건(전체의 31.6%)** → 현재 방식으로 그대로 500 실패.
- 백필 직렬 예상: 건당 median ~73ms → **약 2~2.5시간**(단일 스레드).

## 요구사항

- [ ] **R1 절삭 (버그 수정, 공유 지점)** — 임베딩 전 텍스트를 **단순 substring**으로 안전 마진(기본 6,000자)까지 자른다. 위치는 `OllamaEmbeddingClient.embed()` — 모든 호출자(증분 `upsert`/`findSimilar` + 신규 백필)가 이 지점을 통과하므로 한 곳 수정으로 두 경로의 500 버그를 동시 해소.
- [ ] **R2 절삭 한도 설정화** — 한도는 `EmbeddingProperties`에 `maxChars`(기본 6000) 추가, `application.yml` 환경변수 주입(`EMBEDDING_MAX_CHARS`). 하드코딩 금지(CLAUDE.md §7). 모델 교체 시 코드 무변경 조정.
- [ ] **R3 백필 잡 추적** — `AnalysisBackfillService`(V13) / `ContentBackfillJob`(V25) 패턴 답습: 잡 생성 → 청크 커서 처리 → 진행률 갱신 → 완료/실패 기록. 재시작 시 `last_processed_id` 커서에서 재개.
- [ ] **R4 커서 페이지네이션** — `content_text IS NOT NULL AND id > :lastId ORDER BY id ASC LIMIT :chunk` 로 청크 조회. 청크당 embed + Chroma upsert(rcept_no 멱등). 워터마크 = 청크 마지막 id + 1(실패 건도 커서 전진 → 무한 루프 차단).
- [ ] **R5 안전망** — `safetyCap`(예상 청크 수 ×2) + 조기 중단(50건 시도 후 성공 0건 → Ollama 가용성 문제로 throw). `AnalysisBackfillService`와 동일.
- [ ] **R6 관리자 API** — `POST /admin/analysis/embedding-backfill`(202 + jobId, 실행 중 중복 시 409) / `GET /admin/analysis/embedding-backfill/jobs/{jobId}`(진행률). `/admin/**` HTTP Basic(ROLE_ADMIN) 기존 SecurityConfig 적용.
- [ ] **R7 테스트** — 절삭 단위 테스트(6,700자 초과 → 자름 후 성공) + 백필 잡 Testcontainers IT(커서 전진·진행률 누적·재개). Mock DB 금지(CLAUDE.md §6-6).

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(analysis + infrastructure/llm + disclosure repository)
- **DB 변경**: **Flyway V26 신규** — `embedding_backfill_jobs` 테이블(V25 `content_backfill_jobs` 미러). 최신 적용 버전 V25 확인 완료 → V26이 다음.
- **Chroma**: 컬렉션 스키마 변경 없음(기존 `disclosure_embeddings` 768/cosine 그대로 적재만). 차원 불일치 리스크 없음(동일 nomic-embed-text).
- **외부 계약**: 변경 없음(내부 관리자 API + Ollama/Chroma 기존 연동).

### 신규/수정 파일 (예상 — 카드 분해는 dc-tech-review)

수정:
- `backend/src/main/java/com/dartcommons/infrastructure/llm/OllamaEmbeddingClient.java` — embed() 절삭(R1)
- `backend/src/main/java/com/dartcommons/infrastructure/llm/EmbeddingProperties.java` — `maxChars` 필드(R2)
- `backend/src/main/resources/application.yml` — `dartcommons.llm.embedding.max-chars` + `EMBEDDING_MAX_CHARS`
- `.env.example` — `EMBEDDING_MAX_CHARS` 문서화
- `backend/src/main/java/com/dartcommons/disclosure/repositories/DisclosureRepository.java` — `findIdsWithContentText(lastId, Pageable)` + `countWithContentText()` (R4). 참고: `Stage3RagService`가 이미 `DisclosureRepository`를 주입 중 — analysis→disclosure repository 의존이 기존에 성립.

신규:
- `backend/src/main/resources/db/migration/V26__create_embedding_backfill_jobs.sql` (R3)
- `backend/src/main/java/com/dartcommons/analysis/entities/EmbeddingBackfillJob.java` (R3)
- `backend/src/main/java/com/dartcommons/analysis/repositories/EmbeddingBackfillJobRepository.java` (R3)
- `backend/src/main/java/com/dartcommons/analysis/services/EmbeddingBackfillService.java` (R3~R5) — `@Async("analysisBackfillExecutor")` 재사용(신규 풀 불필요, 폴링 격리 유지)
- `backend/src/main/java/com/dartcommons/analysis/controllers/EmbeddingBackfillController.java` + jobId 응답 DTO (R6)
- 테스트: 절삭 단위(`OllamaEmbeddingClient` 또는 truncate 헬퍼) + `EmbeddingBackfillJobIT`(Testcontainers) (R7)

## 관련 패턴 / 과거 사례

- **잡 추적·커서·safetyCap 표준**: `AnalysisBackfillService`(analysis, V13 `AnalysisJob`) + `DisclosureContentBackfillService`(disclosure, V25 `ContentBackfillJob`). 본 백필은 이 둘의 3번째 인스턴스 — 엔티티/서비스/컨트롤러 구조를 그대로 답습(REQUIRES_NEW 청크 커밋, stale RUNNING 재개, 202/409).
- **Chroma upsert 멱등**: `ChromaClient.upsert(rceptNo, vector, metadata)` — 동일 id 덮어씀. 재실행/중복 안전, 별도 "embedded" 플래그 컬럼 불필요(커서만으로 재개).
- **메타데이터 빌드**: `Stage3RagService.buildMetadata(d)` 재사용 대상 — 백필도 동일 메타 스키마(disclosure_id/corp_code/corp_name/disclosure_type/rcept_dt)를 넣어야 `findSimilar` 파티셔닝/필터가 동작. → **로직 중복 방지: `buildMetadata`를 재사용 가능한 형태로 노출**하거나 백필이 `Stage3RagService.upsert(id)`를 그대로 호출하는 방안 검토(권장 방향 참조).

## 리스크 / 법적 검토

- **절삭에 의한 의미 손실**: 6,000자 초과 문서(31.6%)는 앞부분만 임베딩 → 뒷부분 맥락 유실. DART 공시는 핵심 요지가 앞단(제목·개요)에 오는 구조라 단순 substring 수용 가능(사용자 결정). 향후 정확도 이슈 시 문장경계/청크 분할로 승급 — `ponytail:` 후속 카드로 남김.
- **투자 권유/개인정보**: 해당 없음(임베딩은 공시 원문 벡터, 매수가·보유종목 등 개인정보 미포함).
- **운영 부하**: 백필 2h+ 동안 Ollama 임베딩 점유 → 동시간 신규 공시 증분 분석의 임베딩이 큐 대기 가능. `analysisBackfillExecutor`(core1/max2) 격리로 폴링 분류 자체는 보호되나, 임베딩은 단일 Ollama 인스턴스 공유 — **저부하 시간대 실행 권장**(운영 노트).
- **단일 인스턴스 불변식**: stale RUNNING 재개는 단일 인스턴스 전제(V25와 동일). 다중 인스턴스는 후속 분산 락 Spec 영역.

## 권장 구현 방향

**절삭(R1) 위치 = `OllamaEmbeddingClient.embed()` 단일 지점.** 대안(백필에서만 자르기)은 증분 경로 500 버그를 방치하므로 기각 — 모든 호출자가 통과하는 클라이언트에서 한 번 자르는 것이 최소 diff이자 근본 수정.

**백필의 임베딩 실행은 `Stage3RagService.upsert(disclosureId)` 재호출을 권장.** 이유:
- 임베딩 + Chroma upsert + `buildMetadata` 로직이 이미 그 안에 있어 **중복 구현·메타 스키마 드리프트 방지**(findSimilar가 기대하는 메타와 100% 일치 보장).
- `EmbeddingBackfillService`는 잡 추적·커서·청크 루프만 담당하고, 건별 처리는 `stage3RagService.upsert(id)`에 위임 → `AnalysisBackfillService`가 `stage2Analyzer.analyze(id)`에 위임하는 구조와 대칭.
- 트레이드오프: `upsert(id)`가 내부에서 `findById` 재조회 → 커서 쿼리가 id만 받으므로 건당 1회 추가 SELECT 발생. 67k건에 무시 가능(임베딩 73ms 대비 PK 조회 <1ms). 최적화가 필요하면 후속에 배치 fetch로 승급.

**신규 잡 테이블(V26) vs `content_backfill_jobs` 재사용**: 신규 테이블 권장. `findFirstByStatusOrderByCreatedAtDesc`류 stale 조회가 두 백필 타입 간 충돌하지 않도록 격리 — V13/V25가 이미 도메인별 별도 테이블을 두는 확립된 패턴. 비용은 마이그레이션 1개.

## Tech Review (dc-tech-review · 2026-07-01)

### 아키텍처 분해

- **영향 레이어**: backend — `infrastructure/llm`(절삭 버그 수정) + `disclosure/repositories`(커서 쿼리) + `analysis`(백필 잡 신규). FE/외부 계약 무변경.
- **미러 대상 확정**: 잡 엔티티는 **`ContentBackfillJob`(V25) 미러** — `AnalysisJob`(V13)은 watermark를 로컬 변수로만 유지하고 `last_processed_id`를 영속화하지 않아 재개 불가. 재시작 복구 요구(R3)를 만족하려면 V25 스키마(last_processed_id 컬럼 + `recordChunkProgress(processedDelta, failedDelta, lastId)`)를 따라야 한다.
- **트리거 패턴 확정**: 컨트롤러는 `DisclosureContentBackfillController`의 **`createAndStartAsync()` CAS 원자 메서드**(Optional 반환, empty→409)를 미러. `AnalysisBackfillService`의 2단계(createJob + runAsync)는 컨트롤러 레벨 TOCTOU가 있어 resilience Spec에서 이미 폐기됨 — 신규 코드는 최신 패턴 채택.
- **신규 vs 수정**:
  - 신규: V26 마이그레이션, `EmbeddingBackfillJob`(entity), `EmbeddingBackfillJobRepository`, `EmbeddingBackfillService`, `EmbeddingBackfillController`, 응답 DTO 2종(Start/Progress), 테스트 2종.
  - 수정: `OllamaEmbeddingClient`, `EmbeddingProperties`, `application.yml`, `.env.example`, `DisclosureRepository`.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | **절삭 버그 수정** — `EmbeddingProperties.maxChars`(기본 6000) 추가 → `OllamaEmbeddingClient.embed()`에서 `text.length() > maxChars ? text.substring(0, maxChars) : text` → `application.yml` `max-chars: ${EMBEDDING_MAX_CHARS:6000}` + `.env.example` 문서화 | backend/infrastructure/llm | BE | 하 | - |
| 2 | **커서 쿼리** — `DisclosureRepository.findIdsWithContentText(lastId, Pageable)` (`content_text IS NOT NULL AND (:lastId IS NULL OR d.id > :lastId) ORDER BY d.id ASC`) + `countWithContentText()` | backend/disclosure | BE | 하 | - |
| 3 | **잡 영속화** — V26 `embedding_backfill_jobs`(V25 미러) + `EmbeddingBackfillJob` entity(analysis 도메인, `create/start/recordChunkProgress(…,lastId)/succeed/fail`) + `EmbeddingBackfillJobRepository`(`findByJobId`, `findFirstByStatusOrderByCreatedAtDesc`) | backend/analysis | BE | 중 | - |
| 4 | **백필 서비스** — `EmbeddingBackfillService`: `@Transactional createAndStartAsync()`(CAS: RUNNING 존재 시 empty) → `@Async("analysisBackfillExecutor") runAsync(jobId)` → `resolveResumePoint()`(stale RUNNING 재개) + 청크 루프(건별 `stage3RagService.upsert(id)` 위임) + safetyCap + 조기중단(50건 후 0건→throw) + REQUIRES_NEW 청크 커밋 | backend/analysis | BE | 상 | #2, #3 (#1은 실 성공 전제) |
| 5 | **관리자 API** — `EmbeddingBackfillController`: `POST /admin/analysis/embedding-backfill`(202+jobId / 409) + `GET .../jobs/{jobId}` + `EmbeddingBackfillJobStartResponse`/`…Response` record | backend/analysis | BE | 하 | #4 |
| 6 | **테스트** — 절삭 단위(6,700자↑ 입력 → substring 후 embed 성공, Mock 모델) + `EmbeddingBackfillJobIT`(Testcontainers: 커서 2청크 전진·진행률 누적·stale 재개·CAS 중복 409) | backend | BE | 중 | #1, #4 |

### DB / 마이그레이션 영향

- **신규**: `backend/src/main/resources/db/migration/V26__create_embedding_backfill_jobs.sql` (최신 적용 V25 확인 완료 → V26이 다음). V25 스키마를 컬럼 동일하게 복제, 테이블명·인덱스명만 `embedding_backfill_jobs` / `idx_embedding_backfill_jobs_status`로 교체. status CHECK 4종(PENDING/RUNNING/SUCCEEDED/FAILED) 동일.
- **인덱스**: 커서 쿼리(#2)는 PK(id) range scan + `content_text IS NOT NULL` 필터. 67,847/전체 비율이 높아 부분 인덱스 이득 작음 → **신규 인덱스 불필요**(seq/PK scan 수용). 필요 시 후속.
- Chroma: 스키마 변경 없음(기존 컬렉션에 적재만).

### 외부 계약 영향

- 없음. Ollama 임베딩(`/api/embeddings`)·Chroma upsert 기존 연동 그대로. 신규 REST는 내부 `/admin/**`.

### 리스크 & 법적 검토

- **절삭 의미 손실(수용됨)**: 6,000자↑ 31.6%는 앞부분만 임베딩. DART 공시 요지가 앞단에 오는 구조라 수용. 문장경계/청크 승급은 `ponytail:` 후속 카드.
- **Ollama 점유(운영)**: 백필 ~2h 동안 임베딩 큐 점유 → 동시간 증분 분석의 임베딩 지연 가능. `analysisBackfillExecutor`(core1/max2)가 분류 풀과 격리하나 Ollama 단일 인스턴스는 공유 — **저부하 시간대 실행 권장**.
- **단일 인스턴스 불변식**: `resolveResumePoint()` stale 재개는 단일 인스턴스 전제(V25 동일). 다중 인스턴스는 분산 락 후속 Spec 영역 — Spec 본문 명시.
- **금융 개인정보/자본시장법**: 해당 없음(공시 원문 벡터만, 개인정보 미포함, 투자 표현 무관).

### 예상 wave 수

- **Wave 1 (독립 배포)**: 카드 #1 — 증분 경로의 현행 6,700자↑ 500 버그를 즉시 해소하는 독립 가치. 백필과 무관하게 프로덕션 개선. `dc-review-code` → `dc-push` 단독 진행 가능.
- **Wave 2**: 카드 #2~#6 — 백필 풀스택(#2·#3 병렬 → #4 → #5 → #6).

**구현 판단**: 기존 V13/V25 잡 백필 패턴의 3번째 인스턴스로, 신규 아키텍처 결정 없음(전부 확립된 패턴 답습). 구현 가능. Draft → Approved 전환 권장.

<!-- Tech Review 섹션 끝 -->
