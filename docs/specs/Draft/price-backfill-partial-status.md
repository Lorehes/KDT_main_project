---
type: spec
status: Draft
created: 2026-07-02
updated: 2026-07-02
---

# 주가 백필 PARTIAL 상태 도입 Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

`[[krx-price-timeseries]]` Wave B의 `PriceBackfillService` 안전망(연속 20 평일 빈 응답 → 조기 중단)이 **두 상황을 구분하지 못한다**:

1. **가용 이력의 끝 도달** — KRX/GitHub cache가 과거 특정 시점까지만 데이터를 제공(무료 소스 한계). 그 경계에 닿으면 정상적으로 "받을 수 있는 건 다 받은" 상태.
2. **소스 장애** — KRX·GitHub 전면 다운으로 처음부터 데이터를 못 받는 진짜 실패.

**실측(2026-07-02 백필 실행)**: 최근 4개월(2026-03-09~07-01, 27,917행, 커버 종목 341 전량)을 정상 적재한 뒤, 그 이전(무료 소스 미제공 구간)에서 연속 20 평일 빈 응답 → **FAILED로 종료**. 데이터는 멱등 커밋으로 남았으나 잡 상태가 "실패"라 운영상 "부분 성공했는데 실패로 보이는" 오해 발생.

- 페르소나: 운영자(관리자 백필 실행·모니터링).
- BM 티어: 무관(관리자 인프라).
- 목적: **datesOk>0(일부라도 적재)이면 PARTIAL, datesOk==0(전무)이면 FAILED**로 구분해 잡 상태의 정확성 확보.

## 요구사항

- [ ] `PriceBackfillJob.Status`에 `PARTIAL` 추가 (부분 완료 — 가용 이력 끝 또는 중간 소스 중단이나 일부 적재 성공)
- [ ] 안전망 트리거 시 분기: `datesOk>0` → `PARTIAL`(예외 아님, 정상 종료), `datesOk==0` → `FAILED`(장애, 예외)
- [ ] Flyway V29 — `price_backfill_jobs.status` CHECK 제약에 `'PARTIAL'` 추가
- [ ] PARTIAL 잡도 `lastProcessedDate`·`processed`·`failed`가 정확히 기록되어 재개(resume) 가능 유지
- [ ] Testcontainers IT: 일부 적재 후 연속 빈응답 → PARTIAL / 전건 빈응답 → FAILED

## 영향 범위 (조사 결과)

- 영향 레이어: **backend(stocks)** 단독. FE 무관(관리자 API 상태 문자열만 확장).
- 영향 파일:
  - 신규: `backend/src/main/resources/db/migration/V29__add_partial_status_to_price_backfill_jobs.sql`
  - 수정: `stocks/entities/PriceBackfillJob.java`(Status enum + `partial()` 전이 메서드), `stocks/services/PriceBackfillJobStateService.java`(`partialJob()` REQUIRES_NEW), `stocks/services/PriceBackfillService.java`(안전망 분기 — throw 대신 조건부 partial)
  - 테스트: `backend/src/test/java/com/dartcommons/stocks/PriceBackfillJobIT.java`(PARTIAL 케이스 추가; 기존 `doBackfill_allEmpty_earlyAbortFails`는 datesOk==0이라 FAILED 유지 — 회귀 검증)
- **DB 변경**: **Flyway V29 필요** — CHECK 제약 변경(`ALTER TABLE ... DROP CONSTRAINT ... ADD CONSTRAINT ... CHECK (status IN (…,'PARTIAL'))`). 적용된 V28은 불변 → 새 V29로 제약 교체.
- **외부 계약**: 없음. `PriceBackfillJobResponse.status`는 이미 String이라 PARTIAL 자동 노출.

## 관련 패턴 / 과거 사례

- `PriceBackfillService`(krx-price-timeseries Wave B) 안전망: 현재 `consecutiveEmpty >= EARLY_ABORT_THRESHOLD(20)` → `throw IllegalStateException` → catch → `failJob`.
- Wave B 리뷰(correctness/reliability 에이전트)가 이미 이 뉘앙스를 지적: *"이력 경계 도달을 FAILED 대신 부분 완료로 구분하는 게 낫다"* — 본 Spec이 그 후속.
- `EmbeddingBackfillJob`/`ContentBackfillJob`은 이력 경계 개념이 없어(id 커서, 유한 코퍼스) PARTIAL 불필요 — **price_backfill_jobs에만 국한**(Status enum·CHECK 각 테이블 독립).

## 리스크 / 법적 검토

- 자본시장법/개인정보 무관(관리자 인프라 상태 머신).
- **엣지**: `datesOk>0`이나 실제로는 "중간 전면 장애"일 수도 있음(이력 끝과 구분 불가). → PARTIAL로 표기하되 `lastProcessedDate` 커서로 재개 가능하게 유지(운영자가 재실행 시 이어감). "정확히 이력 끝인지"까지 판별하는 건 소스 메타 부재로 불가 — PARTIAL의 정의를 "일부 적재 성공 + 조기 중단"으로 명확히 문서화.
- **회귀**: 기존 FAILED 케이스(datesOk==0) 동작 보존 — 안전망의 장애 감지 기능 유지.

## 권장 구현 방향

- **안전망 분기(핵심)**: `PriceBackfillService.doBackfill` 루프에서 `consecutiveEmpty >= THRESHOLD` 도달 시:
  - `datesOk > 0` → 남은 진행률 flush + `stateService.partialJob(jobId, "가용 이력 끝 또는 소스 중단 — N일 적재 후 연속 빈응답")` 후 **정상 return**(예외 없음).
  - `datesOk == 0` → 기존대로 `throw IllegalStateException`(FAILED, 진짜 장애).
- **엔티티**: `PriceBackfillJob.partial(reason)` — status=PARTIAL, finishedAt 기록, errorMessage에 사유(마스킹). succeed()와 유사하나 상태만 PARTIAL.
- **Flyway V29**: CHECK 제약을 5종으로 교체. 컬럼/인덱스 무변경.
- 트레이드오프: PARTIAL을 별도 상태로 둘지 vs SUCCEEDED + `hadGaps` 불리언 플래그. **별도 상태 권장** — 운영 모니터링·재실행 판단이 status 한 필드로 명확(불리언은 조회 시 추가 해석 필요).

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
