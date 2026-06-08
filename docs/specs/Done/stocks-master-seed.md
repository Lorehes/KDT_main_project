---
type: spec
status: Done
created: 2026-05-30
updated: 2026-06-08
---

# 종목 마스터 시드/동기화 (stocks) Spec

> 상태: Draft → Approved (2026-06-02) → **Done** (2026-06-08, 341 종목 V10 시드 + StockMasterSyncJob 완료)
> 관계: [[disclosure-collection-pipeline]]의 **선행 작업**(커버 종목 필터가 이 데이터에 의존).
> 연계 SSOT: [[DART공시통역_통합기획서]] §3.1·§7.3 · [[db_schema]] §3.2 · [[api_spec]] §3.1·§3.2 · [[feature_structure]] §4

## 배경 / 목적

- **문제**: `stocks`(V2) 테이블이 비어 있어 ①공시 **커버 종목 필터**([[disclosure-collection-pipeline]])와 ②`portfolios` FK(`stock_code`)가 동작하지 않는다.
- **목적**: 코스피200 + 코스닥150 = **약 350종목**(통합기획서 §3.1) 마스터를 `stocks`에 적재하고, DART 고유번호(`corp_code`)↔KRX 종목코드(`stock_code`)를 매핑한다.
- **BM 티어**: 전 티어 공통 인프라(기준 데이터).

## 요구사항

### 기능
- [ ] KRX 종목 기본정보(코스피200·코스닥150 선정) → `stocks` 적재(`stock_code`·`corp_name`·`market`·`sector`)
- [ ] DART `corpCode.xml`(고유번호 zip)에서 `corp_code` 매핑 → `stocks.corp_code`
- [ ] 분기 1회 동기화 잡(`StockMasterSyncJob`, [[feature_structure]] §4)로 갱신
- [ ] 멱등 upsert(이미 존재하는 `stock_code`는 갱신)

### 비기능
- [ ] 외부 호출 타임아웃·재시도, 키는 환경변수(`DART_API_KEY`·`KRX_API_KEY`)
- [ ] 350종목 선정 기준 명확화(지수 구성 종목 출처)

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(`infrastructure`) + `stocks` 접근 레포(귀속 도메인은 tech-review에서 — 보조 마스터라 `infrastructure` 동기화 잡 + 공용 레포 권장)
- **신규 파일(예상)**:
  - `infrastructure/krx/KrxClient.java`, `infrastructure/dart/DartCorpCodeClient.java`
  - `Stock` 엔티티 + `StockRepository`(귀속 위치 tech-review)
  - `StockMasterSyncJob`
- **DB 변경**: 없음(`stocks` V2 이미 존재). 데이터 적재만
- **외부 계약**: KRX 종목 기본정보 API([[api_spec]] §3.2 — **엔드포인트·파라미터 미확정, 확인 필요**), DART `corpCode.xml`([[api_spec]] §3.1)

## 관련 패턴 / 과거 사례

- 첫 기능군(참고 구현 없음). 대안: **`scripts/data_collection/`(Python) 1회 시드** 후 백엔드 분기 동기화로 운영(통합기획서 §5.4) — tech-review에서 선택.

## 리스크 / 법적 검토

- 법적: 공개 기준 데이터 — 자본시장법/개인정보 무관.
- 운영:
  - **확인 필요**: KRX OpenAPI 정확한 서비스 경로·파라미터([[api_spec]] §3.2 미확정).
  - 코스피200/코스닥150 **구성 종목 출처**(지수 편입 리스트) 확정 필요 — 분기 리밸런싱 반영.
  - `corp_code`↔`stock_code` 매핑 누락/불일치(비상장·우선주 제외, 통합기획서 §3.1).

## 권장 구현 방향

- **MVP**: 350종목 **1회 시드**(스크립트 또는 부팅 시 적재) → 즉시 [[disclosure-collection-pipeline]] 필터 가능하게.
- **운영**: 분기 `StockMasterSyncJob`으로 자동 갱신(후속).
- 시드 데이터 소스(KRX API vs 공개 CSV vs 스크립트)는 tech-review에서 확정.

## Tech Review (dc-tech-review · 2026-06-02)

### 아키텍처 분해
- **영향 레이어**: backend(`stocks` 신규 도메인, `infrastructure/krx`·`infrastructure/dart` 확장, `disclosure` 회귀)
- **프론트**: 영향 없음
- **신규 도메인 결정**: `stocks/`를 **별도 도메인**으로 도입(`entities/` · `repositories/` · `services/`). 근거: `shared/`는 도메인-무관 인프라 전용([[CLAUDE]] §3-2), `infrastructure/`는 외부 호출 격리용. `stocks`는 비즈니스 마스터이므로 도메인.
- **신규**:
  - `stocks/entities/Stock.java` — V2 `stocks` 매핑(stock_code PK, corp_code UNIQUE, corp_name, market, sector)
  - `stocks/repositories/StockRepository.java` — `findAllStockCodes(): Set<String>`(N+1 해결), `existsByStockCode`, `upsert`
  - `stocks/services/StockMasterService.java` — 시드/upsert 오케스트레이션
  - `infrastructure/dart/DartCorpCodeClient.java` — `corpCode.xml` zip 다운로드·언집·파싱
  - `infrastructure/krx/KrxClient.java` — 종목 기본정보(시장/섹터) ※ 엔드포인트 #1 실측
  - `infrastructure/krx/KrxApiProperties.java`
  - `stocks/services/StockMasterSyncJob.java` — `@Scheduled(cron)` 분기 1회
  - `scripts/data_collection/seed_stocks.py` — 코스피200+코스닥150 리스트 → CSV(initial seed source)
  - `backend/src/main/resources/db/migration/V10__seed_stocks.sql` — 1회 시드(또는 `data.sql` 회피, 마이그레이션 채택)
- **수정**:
  - `disclosure/services/DisclosureCollectionService.java` — `isCovered()` JdbcTemplate native query → `StockRepository.findAllStockCodes()` Set 1회 로드(**deferred HIGH N+1 동시 해결**)
  - `application.yml` — `dartcommons.krx.*` 추가, Caffeine 캐시 키 `stocks` 등록([[feature_structure]] §6)
  - `DisclosureCollectionIntegrationTest` — fixture 방식 변경(엔티티 직접 저장)

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | **데이터 출처 확정**: 코스피200/코스닥150 구성 종목 리스트 출처 결정(KRX 공식 CSV vs 데이터센터 API vs 인덱스 산출 파일). KRX OpenAPI 종목 기본정보 엔드포인트/파라미터 **실측 검증** + [[api_spec]] §3.2 확정 갱신 | infrastructure(조사) | BE | 중 | KRX 키 발급 |
| 2 | `KrxApiProperties` + `application.yml` `dartcommons.krx.*` 설정(타임아웃·base-url·키 env) | config | BE | 하 | #1 |
| 3 | `infrastructure/dart/DartCorpCodeClient` — `corpCode.xml` zip 다운로드 → 언집 → 파싱(corp_code↔stock_code 사전 구축). 키는 기존 `DartApiProperties` 재사용 | infrastructure | BE | 중 | #2 |
| 4 | `infrastructure/krx/KrxClient` — 종목 기본정보 호출(`market`/`sector`). RestClient + 타임아웃 + 재시도(deferred HIGH "Thread.sleep → spring-retry" 결정 결과 따름. 미정 시 잡 측 fixed backoff) | infrastructure | BE | 중 | #1,#2 |
| 5 | `scripts/data_collection/seed_stocks.py` — 코스피200+코스닥150 리스트(소스 #1) + DART corpCode.xml 매핑 → `seed_stocks.csv` 산출(검증 가능한 산출물) | scripts | BE/운영 | 중 | #1,#3 |
| 6 | `Stock` 엔티티 + `StockRepository`(`findAllStockCodes`·`existsByStockCode`·`save`). V2 스키마 매핑, `ddl-auto: validate` 정합 | stocks/entities·repositories | BE | 하 | - |
| 7 | `V10__seed_stocks.sql` — #5 CSV → INSERT(또는 ON CONFLICT DO UPDATE upsert). 멱등성 보장. **운영 환경에서는 1회 적재** | db/migration | BE | 하 | #5,#6 |
| 8 | `StockMasterService` — 시드 로딩 후 `corp_code`/`market`/`sector` 갱신 로직(upsert), Caffeine 캐시(`@Cacheable("stocks")`) | stocks/services | BE | 중 | #3,#4,#6 |
| 9 | `StockMasterSyncJob` — `@Scheduled(cron)` 분기 1회(`"0 0 4 1 1,4,7,10 *"`), 예외 비중단, **수동 트리거 메서드 제공**(편입제외 리밸런싱 대응) | stocks | BE | 중 | #4,#8 |
| 10 | **disclosure 회귀 리팩토링**: `DisclosureCollectionService.isCovered()` → `StockRepository.findAllStockCodes()` Set 사용. **deferred HIGH N+1 해결** + `review-findings-deferred.md`에서 해당 항목 제거 | disclosure(수정) | BE | 하 | #6 |
| 11 | 통합 테스트(Testcontainers): #6 엔티티 매핑 정합 + #7 시드 idempotent 적용 + #10 disclosure 회귀(stocks fixture 대신 실 엔티티 save) | test | BE | 중 | #6,#7,#10 |
| 12 | 문서 정합: [[api_spec]] §3.2 KRX 엔드포인트 확정 갱신 + [[feature_structure]] §4 `StockMasterSyncJob` 상세 보강 + [[CLAUDE]] §3 도메인 목록에 `stocks` 추가 | docs | BE | 하 | #1,#9 |

### DB / 마이그레이션 영향
- **신규 마이그레이션 1건**: `V10__seed_stocks.sql` — 약 350행 INSERT. `ON CONFLICT (stock_code) DO UPDATE` 패턴으로 멱등 적재.
- **스키마 변경 없음** — `stocks`(V2) 테이블 그대로 사용. `ddl-auto: validate`로 `Stock` 엔티티 ↔ V2 정합 가드.
- **선택지**: 시드를 Flyway 마이그레이션이 아닌 부팅 시 `ApplicationRunner`로 처리할 수도 있으나, **마이그레이션 채택 권장**(불변·재현성·다환경 동일성). 분기 리밸런싱은 #9 잡으로 처리.

### 외부 계약 영향
- **신규 소비**:
  - DART `corpCode.xml`([[api_spec]] §3.1) — 분기/초기 1회. zip(`zip` → XML 파싱).
  - KRX 종목 기본정보([[api_spec]] §3.2) — **엔드포인트/파라미터 미확정, #1 실측으로 확정**.
- **기존 계약**: DART `list.json`(disclosure) 변경 없음. 카카오/LLM 무관.
- **데이터 소스 의존**: 코스피200/코스닥150 **분기 리밸런싱**(편입/제외) → #9 잡에서 종목 추가/비활성화 처리. 편입 제외된 종목이 `portfolios`에 남아 있을 경우 FK는 유지하되 마스터에서 비활성 플래그 추가 검토(본 Spec 범위 외, 후속 Spec 제안).

### 리스크 & 법적 검토
- **법적**: 공개 기준 데이터, 자본시장법/개인정보 무관.
- **운영 리스크**:
  - **KRX 엔드포인트 미확정**(#1) — 키 발급 + 실측 없이는 wave 2 진입 불가. 차선책: 한국거래소 공개 CSV(`data.krx.co.kr`) 다운로드 스크립트로 시드만 우선 가능.
  - **분기 리밸런싱**: 편입 제외 종목 + portfolios FK 잔존 — 본 Spec은 마스터 갱신만, **사용자 알림/마이그레이션 정책은 후속 Spec** 권장.
  - **DART corpCode.xml 사이즈**: 약 100k 기업 → 메모리 파싱 시 OOM 주의(스트리밍 파서 또는 필터링 후 적재).
  - **시드 멱등성**: V10 마이그레이션 적용 후 #9 잡이 같은 데이터로 덮어쓸 때 `updated_at`만 변경되도록(불필요한 행 변경 회피 옵션).
  - **disclosure 회귀(#10)**: 기존 통합 테스트가 native query 기반이라 fixture 방식 변경 필요 — #11에서 동시 수정.
- **확인 필요**:
  - KRX API 키 발급 상태 + 엔드포인트(#1)
  - 코스피200/코스닥150 **공식 구성 데이터 출처** (KRX 인덱스 산출, 분기 발표)

### 예상 wave 수 — 3 wave
- **wave 1 (인프라·데이터)**: #1 실측 → #2 설정 → #3 DartCorpCodeClient → #4 KrxClient → #5 seed CSV 산출
- **wave 2 (엔티티·시드·회귀)**: #6 Stock 엔티티/레포 → #7 V10 시드 마이그레이션 → #10 disclosure 회귀(deferred HIGH N+1 동시 해결) → #11 통합 테스트
- **wave 3 (동기화·문서)**: #8 StockMasterService → #9 SyncJob → #12 문서 정합 + [[disclosure-collection-pipeline]] Done 전환 검토(deferred 잔여 5건 별도 처리)

### 구현 가능 판단
- ⚠️ **조건부 구현 가능**. wave 1 #1(KRX 엔드포인트 실측)을 먼저 수행해야 wave 2~3 확정 가능.
- 차선책: **코스피200/코스닥150 시드만 공개 CSV로 우선 적재**(#5~#7) → disclosure deferred N+1 즉시 해결(#10~#11) → KRX 동기화 잡(#8~#9)은 #1 확정 후 별도 wave.
- **Draft → Approved 권장** (단, 사용자에 #1 실측 진입 여부 또는 차선책 채택 결정 요청).

