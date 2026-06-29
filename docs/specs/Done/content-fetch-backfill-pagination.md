---
type: spec
status: Done
created: 2026-06-29
updated: 2026-06-29
---

# content-fetch-backfill-pagination Spec

> 상태: Draft → Approved → **Done** (2026-06-29, 구현 완료 — SHA fb82e31)
> 원출처: dc-review-code MEDIUM-1 이슈 — disclosure-content-text-fetch Spec 구현 리뷰

## 배경 / 목적

`DisclosureContentBackfillService.runBackfill()`이 `findPendingContentFetchIds()`로 `content_fetched_at IS NULL`인 공시 ID 전체를 단일 쿼리로 메모리에 로드한다.
93k+ 건 기준 힙 점유 ~3–5MB + DB 장시간 스캔 1회 발생.
백필 중 신규 pending 공시가 추가되어도 이미 로드된 List를 순회하므로 갱신 반영 안 됨.

**목표**: AnalysisBackfillService의 검증된 커서 기반 청크 패턴을 채택해 메모리·DB 부하를 분산하고, 백필 중 추가된 pending 행도 자동 포함.

영향 티어: 내부 운영 인프라 (Free/Pro/Premium 모두 간접 수혜 — Stage 3 RAG 활성화 전제 조건)

## 요구사항

- [ ] `DisclosureRepository`에 `findPendingContentFetchIds(Long lastId, Pageable pageable)` 추가 — `content_fetched_at IS NULL AND id > :lastId ORDER BY id ASC`
- [ ] `DartApiProperties`에 `contentBackfillChunkSize` 속성 추가 (기본값 100, ENV `CONTENT_BACKFILL_CHUNK_SIZE`)
- [ ] `application.yml`에 `content-backfill-chunk-size: 100` 추가
- [ ] `DisclosureContentBackfillService.runBackfill()`을 while 루프 + 워터마크 패턴으로 교체
  - 워터마크: `lastId = null`로 시작, 청크 처리 후 `lastId = ids.get(ids.size()-1)`
  - Safety cap: `contentFetchedAt IS NULL`인 전체 건수 COUNT + 청크수 × 2 상한 (무한루프 방지)
  - 진행 로그: 청크 완료마다 `processed / estimated` 기록
- [ ] 기존 무인수 `findPendingContentFetchIds()` 제거 (사용처 없음 확인 후)
- [ ] `DisclosureContentBackfillController`의 POST/GET API는 변경 없음 (runBackfill/isRunning 시그니처 유지)

## 영향 범위 (조사 결과)

- 영향 레이어: backend(disclosure)
- 영향 파일:
  - `backend/src/main/java/com/dartcommons/disclosure/repositories/DisclosureRepository.java`
  - `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureContentBackfillService.java`
  - `backend/src/main/java/com/dartcommons/infrastructure/dart/DartApiProperties.java`
  - `backend/src/main/resources/application.yml`
- DB 변경: 없음 (인덱스 `idx_disclosures_content_pending`이 `WHERE content_fetched_at IS NULL`을 이미 커버 — V24)
- 외부 계약 변경: 없음

## 관련 패턴 / 과거 사례

- 기존 구현 참고: `backend/src/main/java/com/dartcommons/analysis/services/AnalysisBackfillService.java`
  - 워터마크 패턴: `watermark = ids.get(ids.size()-1) + 1`, `PageRequest.of(0, chunkSize)`
  - Safety cap: `(chunksTotal + 1) * 2`
  - 청크 중단 기준: 연속 실패 50건 & 성공 0건 → IllegalStateException
- 기존 구현 참고: `backend/src/main/java/com/dartcommons/analysis/repositories/AnalysisResultRepository.java`
  - `findUnanalyzedDisclosureIds(lastId, idTo, pageable)` — JPQL + Pageable 조합 패턴

## 리스크 / 법적 검토

- **정렬 변경 영향**: 현재 `ORDER BY rceptDt DESC`(최신 공시 우선) → `ORDER BY id ASC`(등록 순)로 변경됨. 운영상 처리 완결성이 최신 우선보다 중요하므로 허용.
- **Safety cap 미설정 시 무한루프**: `content_fetched_at` 업데이트가 CAS 실패로 영구적으로 null에 머물면 동일 행을 계속 조회. Safety cap으로 방어 필수.
- **concurrent runBackfill 시나리오**: AtomicBoolean CAS가 이미 적용되어 있으므로 이중 실행 없음 (HIGH-6 fix).
- 법적 제약: 해당 없음 (운영 인프라)

## 권장 구현 방향

**방법 A (채택): 커서 기반 워터마크** — AnalysisBackfillService와 동일 패턴

```java
// DisclosureRepository에 추가
@Query("SELECT d.id FROM Disclosure d WHERE d.contentFetchedAt IS NULL AND (:lastId IS NULL OR d.id > :lastId) ORDER BY d.id ASC")
List<Long> findPendingContentFetchIds(@Param("lastId") Long lastId, Pageable pageable);

// DisclosureRepository에 추가 (total 산출용)
@Query("SELECT COUNT(d.id) FROM Disclosure d WHERE d.contentFetchedAt IS NULL")
long countPendingContentFetch();
```

```java
// DisclosureContentBackfillService.runBackfill() 교체
long total = disclosureRepository.countPendingContentFetch();
int chunkSize = props.contentBackfillChunkSize();
int safetyCap = (int) (total / chunkSize + 2) * 2;
Long lastId = null;
int processed = 0;

for (int i = 0; i < safetyCap; i++) {
    List<Long> ids = disclosureRepository.findPendingContentFetchIds(lastId, PageRequest.of(0, chunkSize));
    if (ids.isEmpty()) break;
    for (Long id : ids) {
        disclosureContentService.fetchAndSave(id);
        processed++;
        // ... throttle, interrupt check, progress log
    }
    lastId = ids.get(ids.size() - 1);
}
```

**방법 B (미채택): 오프셋 0 반복** — 처리된 행이 WHERE 절에서 빠지므로 page 0 반복으로 다음 배치 조회. 단순하나 중간에 실패한 행(CAS null → 재시도 안 됨)이 영원히 첫 배치에 잡힐 경우 무한루프 위험. Safety cap만으로 불충분.

## Tech Review (dc-tech-review · 2026-06-29)

### 아키텍처 분해

- 영향 레이어: backend(disclosure) + backend(infrastructure/dart — properties만)
- 신규: `DisclosureRepository.findPendingContentFetchIds(Long lastId, Pageable)`, `countPendingContentFetch()`, `DartApiProperties.contentBackfillChunkSize`
- 수정: `DisclosureContentBackfillService.runBackfill()`(루프 교체), `application.yml`(속성 1줄)
- 제거: 기존 무인수 `findPendingContentFetchIds()` (현재 백필 서비스 단독 사용 — 호출부 교체와 동시 제거)
- DB 마이그레이션: **불필요** — PK(id) 인덱스가 `id > lastId` 범위 + `id ASC` 정렬을 모두 커버. V24 부분 인덱스(`WHERE content_fetched_at IS NULL`)는 필터 조건에 보조 활용.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `findPendingContentFetchIds(lastId, Pageable)` + `countPendingContentFetch()` 쿼리 추가 | backend/disclosure(repo) | BE | 하 | - |
| 2 | `DartApiProperties.contentBackfillChunkSize`(기본 100) + `application.yml` `content-backfill-chunk-size` 추가 | backend/infra + resources | BE | 하 | - |
| 3 | `runBackfill()` 워터마크 while 루프 교체 (total COUNT → safety cap → 커서 전진, throttle/interrupt/progress 보존) | backend/disclosure(service) | BE | 중 | #1, #2 |
| 4 | 무인수 `findPendingContentFetchIds()` 제거 + 컴파일·테스트 검증 | backend/disclosure | BE | 하 | #3 |

### DB / 마이그레이션 영향

- Flyway 마이그레이션 **불필요**. 신규 컬럼·인덱스 없음.
- 쿼리 플랜: `WHERE content_fetched_at IS NULL AND id > :lastId ORDER BY id ASC LIMIT :chunkSize` — PK 인덱스 range scan. 부분 인덱스 `idx_disclosures_content_pending`(V24)는 `content_fetched_at IS NULL` 필터에 활용 가능하나, 정렬이 `rcept_dt DESC`로 정의돼 있어 `id ASC` 정렬에는 PK가 우선. 추가 인덱스 불필요(MVP 규모 93k).

### 외부 계약 영향

- 없음. DART/KRX/카카오/LLM 계약 변경 없음. 관리자 엔드포인트(`/admin/disclosures/content-backfill`) 시그니처 유지(`runBackfill()`/`isRunning()` 불변).

### 리스크 & 법적 검토

- **transient 실패 재시도 동작**: `RestClientException`(일시 실패) 시 `content_fetched_at`이 null로 남으나, 워터마크가 `id > lastId`로 전진하므로 **같은 run 내 재시도 안 됨**. 현재 in-memory 방식도 1회만 순회하므로 동작 동등(회귀 아님). 다음 백필 트리거 시 재처리됨 — 의도된 동작으로 명시.
- **워터마크 전진 불변식**: 청크 처리 후 실패 여부와 무관하게 `lastId = ids.get(last)`로 전진해야 무한루프 없음. AnalysisBackfillService와 동일 불변식.
- **safety cap 연산자 우선순위**: `(int)(total/chunkSize + 2) * 2`는 `((int)(total/chunkSize+2))*2`로 평가됨(의도대로). 구현 시 괄호 명시 권장.
- **resilience Spec 의존**: 본 Spec은 `content-fetch-backfill-resilience`의 `last_processed_id` 재개 기능의 **선행 조건**. 본 Spec 먼저 구현.
- 법적 제약: 해당 없음 (운영 인프라, 사용자 노출 없음, 환각·자본시장법 무관).

### 예상 wave 수

- **1 wave** — 4개 카드 모두 동일 파일군(repo→properties→service→cleanup), 의존성 선형. PR 1개로 충분.

### 검토 결론

구현 가능. AnalysisBackfillService의 검증된 패턴을 답습하므로 리스크 낮음. Draft → Approved 전환 권장.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
