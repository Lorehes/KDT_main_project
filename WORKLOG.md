---
type: worklog
status: active
created: 2026-06-02
updated: 2026-06-02
---

# WORKLOG

> 세션 단위 작업 기록. dc-push가 자동 갱신. dc-handoff의 데이터 소스.

---

## 2026-06-02 | stocks 도메인 도입 + disclosure N+1 해소

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 2 핵심)

### 완료
- `stocks` 도메인 신규 도입 (`Stock` 엔티티 V2 매핑, `StockRepository`)
- `StockRepository.findAllStockCodes()` — 배치당 1회 Set 로드로 deferred HIGH N+1 해소
- `KrxApiProperties` 스켈레톤 + `application.yml` `dartcommons.krx.*`
- `DisclosureCollectionService` — JdbcTemplate 제거, StockRepository 의존, `collectSingle` 시그니처 변경(coveredCodes Set 파라미터)
- `DisclosureCollectionIntegrationTest` — fixture를 `stockRepository.save()`로 전환 (6/6 통과)
- `review-findings-deferred.md` — N+1 항목 제거

### 결정 (코드에 드러나지 않는 사항)
- **아키텍처 예외 (CLAUDE.md §3-2)**: 마스터 데이터 도메인(`stocks/`)은 다른 도메인이 read-only로 직접 의존 허용. write는 마스터 도메인 내부 한정. 옵션 B(shared facade)는 indirection만 늘려 기각.
- **market 필드**: String 유지 (enum 미사용). V10 시드/SyncJob과 동시 도입 예정.
- **KRX 키 발급 완료**, 출처: KRX 공개 CSV (data.krx.co.kr)

### 미완료 (다음 세션)
stocks-master-seed Wave 1 + 3년치 백필 가능 상태:
- `#1` KRX 실측 (엔드포인트/파라미터 확정) + `api_spec.md` §3.2 갱신
- `#3` `DartCorpCodeClient` (corpCode.xml zip 다운로드 + 파싱)
- `#4` `KrxClient` (RestClient + 종목 기본정보)
- `#5` `scripts/data_collection/seed_stocks.py` (KRX 공개 CSV + DART 매핑 → V10 SQL 출력)
- `#7` `V10__seed_stocks.sql` (스크립트 결과 → 적용)
- `#8` `StockMasterService` (upsert 오케스트레이터)
- `#9` `StockMasterSyncJob` (@Scheduled 분기 1회)
- **백필 진입점**: `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill` (3년치 수집 가능해짐)
- **DART 윈도우 청크 분할**: 3개월 단위 + 속도 제어
- **DB 청크 적재 최적화**: 건별 save → saveAll 배치

### deferred 잔여 (review-findings-deferred.md)
- HIGH: `Thread.sleep` 블로킹 재시도 (spring-retry 의존성 추가 결정 후)
- HIGH: `disclosure` → `infrastructure` DTO 직접 의존 (아키텍처 리팩 Spec)
- MEDIUM: `lastPolledDate` 인메모리 (멀티 인스턴스 전환 시점)
- MEDIUM: SSRF 화이트리스트 부재
- LOW: API 키 에러 로그 노출

---

## 2026-06-02 | Wave 1 + 3: stocks 시드 파이프라인 + 동기화 잡

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 1 #3/#4/#5 + Wave 3 #8/#9)

### 완료
- `scripts/data_collection/seed_stocks.py` — pykrx로 KOSPI200/KOSDAQ150 인덱스 구성 + DART corpCode 매핑 → V10 SQL 자동 생성 (`ON CONFLICT DO UPDATE` 멱등)
- `scripts/data_collection/requirements.txt` (pykrx, requests) + `README.md` 실행 가이드
- `DartCorpCodeClient` — corpCode.xml zip 다운로드 + StAX 스트리밍 파싱(메모리 효율) + zip 매직넘버 검사로 JSON 에러 응답 분기
- `KrxClient` skeleton — Spec 카드 #1 실측 placeholder, `fetchAllBasicInfo()`는 빈 Map 반환 + WARN 로그
- `StockMasterService` — DART corp_code + KRX 기본정보 결합해 기존 stocks 행 upsert (신규 편입 종목 자동 추가 안함 — V10/별도 정책 분리, 편입 제외 종목 삭제 안함 — portfolios FK 보호)
- `StockMasterSyncJob` — `@Scheduled(cron = "0 0 4 1 1,4,7,10 *" KST)` 분기 1회, 예외 비중단
- 통합 테스트 7/7 통과 (DartcommonsApplicationTests + DisclosureCollectionIntegrationTest 6건)

### 결정 (코드에 드러나지 않는 사항)
- **V10 시드 SQL은 코드에 미포함** — 스크립트 실행 산출물(`seed_stocks.py` 산출). 사용자가 1회 실행 → SQL 생성 → 커밋 흐름.
- **분기 SyncJob은 기존 행 갱신만** — 신규 편입은 V10 마이그레이션(또는 후속 분기 V{n}), 편입 제외 삭제는 후속 Spec(사용자 알림 + portfolios FK 마이그레이션).
- **KrxClient placeholder**: #1 실측 미완료. 빈 Map 반환 + WARN 로그. SyncJob 호출되더라도 DART corp_code만 갱신, sector 무변화. #1 실측 후 KrxApiProperties endpoint 필드 추가하고 placeholder만 교체하면 됨(컴파일 영향 최소화).
- DART corpCode 응답 zip 매직넘버(`0x50 0x4B`) 검사로 JSON 에러 응답(키 오류 등) 빠르게 분기.

### 미완료 (다음 세션)
**남은 카드**:
- `#1` KRX 정확한 엔드포인트 실측 + `api_spec.md` §3.2 갱신 (네트워크 호출 필요)
- `#7` 사용자가 `seed_stocks.py` 실행 → V10 SQL 생성 → 커밋
- `#12` `feature_structure.md` §4 / `api_spec.md` §3.2 / `db_schema.md` 갱신

**3년치 백필 진입점 (별도 Spec 권장)**:
- `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill?from=&to=`
- DART 윈도우 3개월 청크 분할 + DB saveAll 배치
- `Thread.sleep` 블로킹 재시도 → `spring-retry` 도입 (deferred HIGH 동시 해결)

---

## 2026-06-02 | spring-retry + BackfillService + KrxClient 실구현

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (#1 KRX 부분) + 신규 백필 경로

### 완료
- **spring-retry 도입** (build.gradle: `spring-retry` + `spring-aspects`, `SchedulingConfig`에 `@EnableRetry`)
- **`DartClient` 리팩토링** — `Thread.sleep` 제거. 페이지 호출은 `DartPageFetcher` 내부 `@Component`로 분리(AOP 프록시 제약 회피) + `@Retryable(maxAttempts=3, exponential backoff)`. **deferred HIGH 해결**
- **`KrxClient` 실구현** — pykrx 검증 엔드포인트(`data.krx.co.kr/comm/bldAttendant/getJsonData.cmd`, bld=`MDCSTAT01901`) POST 폼 호출 + JSON `OutBlock_1` 파싱. `@Retryable`. 검증 미완료 명시(비공식 API).
- **`DisclosureBackfillService`** — 90일 청크 분할(`WINDOW_DAYS=90`), 청크별 트랜잭션, saveAll 배치(`CHUNK_SIZE=500`), 이벤트 발행 옵션(기본 false — 백필 시 analysis 큐잉 폭주 방지)
- **`DisclosureBackfillController`** — `POST /admin/disclosures/backfill?from=&to=&emitEvents=false`
- **`application.yml`** — `hibernate.jdbc.batch_size=500` + `order_inserts/updates=true`
- **`api_spec.md` §3.2 갱신** — KRX 공개 데이터 API 패턴 + 검증 필요 마커. DART list.json 표에 백필 라인 추가
- **`DisclosureBackfillServiceTest`** — 청크 분할 / 커버 필터 / 500+ 배치 / 멱등 (4건 통과)
- 전체 테스트 **11/11 통과** (Application + Disclosure 6 + Backfill 4)

### 결정 (코드에 드러나지 않는 사항)
- **백필 경로는 이벤트 미발행 기본** — 3년치 65k+ 건이 분석 도메인 큐로 쏟아지면 폭주. 백필 후 analysis는 별도 배치로 트리거 권장.
- **`DartPageFetcher` 내부 클래스 분리** — `@Retryable`은 AOP 프록시 기반이라 same-class self-invocation 우회. 별도 `@Component`로 분리해 정상 작동.
- **`KrxClient` 패턴은 pykrx 기반 추정** — KRX `data.krx.co.kr`는 비공식 인터페이스라 사전 공지 없이 변경 가능. 운영 환경에서 실측 1회 권장(통합 테스트 또는 curl). 시드 산출은 pykrx에 위임(seed_stocks.py)해 안정성 확보.
- **`BackfillController` 인증 미적용** — Spring Security 미도입 상태. 운영 배포 전 `@PreAuthorize("hasRole('ADMIN')")` 필수.

### 운영 사용 흐름
```bash
# 1. stocks 시드
cd scripts/data_collection && python seed_stocks.py
git add backend/src/main/resources/db/migration/V10__seed_stocks.sql && git commit -m "..."
./gradlew bootRun

# 2. 3년치 백필 (별도 터미널)
curl -X POST "http://localhost:8080/admin/disclosures/backfill?from=2023-06-01&to=2026-06-01&emitEvents=false"
# → 13개 청크 × 평균 ~5k건 = ~65k건 적재, DART rate limit 여유
```

### 미완료
- KRX 운영 실측 (사용자 환경에서 1회 검증 후 `KrxClient.parseResponse()` 필드명 조정)
- `BackfillController` 인증 가드 (Spring Security 도입 시점)
- 비동기 백필 (현재 동기 — 큰 범위는 HTTP 타임아웃 위험)
- KRX 일별 시세 + Stage 3 5일 반응 (Spec 분리)
