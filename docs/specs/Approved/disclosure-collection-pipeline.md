---
type: spec
status: Approved
created: 2026-05-30
updated: 2026-05-30
---

# DART 공시 수집 파이프라인 (Stage 1) Spec

> 상태: Draft → **Approved** (2026-05-30, dc-tech-review 승인)
> 연계 SSOT: [[DART공시통역_통합기획서]] §4.4·§5.1·§6.1 · [[db_schema]] §3.2·§3.4 · [[api_spec]] §3.1 · [[feature_structure]] §2·§4·§6 · [[CLAUDE]] §4

## 배경 / 목적

- **문제**: 서비스의 모든 가치(분석·알림)는 공시 원문 수집에서 출발한다. 신규 공시를 실시간 감지·정규화하는 데이터 토대가 없으면 이후 Stage 2~5가 성립하지 않는다.
- **페르소나**: 전체(A~F) — 공시 수신이 모든 흐름의 출발점.
- **BM 티어**: **전 티어 공통 인프라**(Stage 1은 티어 무관, 공시당 1회 수행). 통합기획서 §3.3.
- **로드맵 위치**: 통합기획서 §16 Phase 1 최상단 "DART OpenAPI 수집 파이프라인 구축".
- **범위**: 신규 공시 감지 → 메타 룰 추출 → 유형 분류 → 커버 종목 필터 → `disclosures` 멱등 적재 → `DisclosureCollectedEvent` 발행. **LLM 미사용**(Stage 1은 룰 기반).

## 요구사항

### 기능
- [ ] `@Scheduled` **1분** 폴링으로 DART `list.json` 신규 공시 감지([[feature_structure]] §4 `DisclosurePollingJob`)
- [ ] 메타데이터 **룰 추출**: `rcept_no`·`corp_code`·`corp_name`·`report_nm`·`rcept_dt` — **DART 원본 그대로**(LLM 변형 금지, [[CLAUDE]] §4)
- [ ] 공시 유형 분류 → `disclosure_type` (룰 기반: `pblntf_ty` + `report_nm` 매칭)
- [ ] 커버 종목 필터: `stocks` 마스터 조인, 미커버 종목 **skip**(통합기획서 §3.1 코스피200+코스닥150)
- [ ] **`rcept_no` 멱등 적재**: 이미 존재하면 skip(중복 발송 방어 1차, [[db_schema]] §3.4 UNIQUE)
- [ ] 수집 성공 시 `DisclosureCollectedEvent(disclosureId)` 발행 (분석 트리거 — **리스너는 analysis 도메인 후속**)
- [ ] DART 응답 `status` 코드 분기(`000` 정상 / `013` 데이터없음 정상 skip / `020`·`800`·`900` 에러 로깅·운영 신호)

### 비기능
- [ ] 외부 호출 **타임아웃 + 지수 백오프 재시도**, 폴링 잡은 예외에 중단되지 않음([[CLAUDE]] §4)
- [ ] 페이지네이션 처리(`page_no`/`page_count`, `total_count` 초과분)
- [ ] 콜드스타트(최초 폴링) 윈도우 정의(당일 `bgn_de=end_de=today`)
- [ ] DART 키는 환경변수(`DART_API_KEY`)로만 주입, 하드코딩 금지([[CLAUDE]] §7)

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(`disclosure`, `infrastructure`, `shared`) — 프론트 영향 없음
- **신규 파일(그린필드 — 현재 두 도메인 모두 `package-info.java`만 존재)**:
  - `backend/.../disclosure/entities/Disclosure.java` — `disclosures`(V4) 매핑 **최초 엔티티**(부트스트랩 1-A 결정대로 엔티티는 기능 단계 도입)
  - `backend/.../disclosure/repositories/DisclosureRepository.java` — `existsByRceptNo` 등
  - `backend/.../disclosure/services/DisclosureCollectionService.java` — 추출·분류·필터·적재 오케스트레이션
  - `backend/.../disclosure/services/DisclosureTypeClassifier.java` — 유형 룰 분류
  - `backend/.../disclosure/DisclosurePollingJob.java` — `@Scheduled` 폴링
  - `backend/.../infrastructure/dart/DartClient.java` — DART 호출(WebClient/RestClient)
  - `backend/.../infrastructure/dart/DartApiProperties.java` — `@ConfigurationProperties(dartcommons.dart)`
  - `backend/.../infrastructure/dart/dto/DartListResponse.java` — list.json 응답 파싱(스키마)
  - `backend/.../shared/event/DisclosureCollectedEvent.java`
  - `backend/.../shared/config/SchedulingConfig.java` 또는 앱에 `@EnableScheduling`
- **수정 파일**:
  - `backend/build.gradle` — **변경 불필요**(RestClient 채택 = `spring-web` 내장, webflux 미추가)
  - `backend/src/main/resources/application.yml` — `dartcommons.dart.*`(base-url·키·타임아웃) 추가 ※ **구현(dc-implement) 단계 작업** — 계획 중 미수행
  - `backend/.../DartcommonsApplication.java` 또는 별도 config — `@EnableScheduling` ※ **구현 단계 작업** — 계획 중 미수행
- **DB 변경**: **없음** — `disclosures`(V4)·`stocks`(V2) 이미 존재. `stocks` 시드는 **선행 Spec [[stocks-master-seed]]로 분리**(본 Spec은 적재 완료 전제)
- **외부 계약**: DART OpenAPI `list.json`([[api_spec]] §3.1). 본문(document.xml)은 이번 범위 밖

## 관련 패턴 / 과거 사례

- `docs/solutions` 없음, AGENT.md 없음 — **첫 기능**(참고 구현 없음). 프로젝트는 [[CLAUDE]]/[[db_schema]]/[[feature_structure]]를 SSOT로 사용
- 설계 근거: [[feature_structure]] §2(수집→분석→발송 시퀀스)·§4(스케줄러 표)·§6(`DartClient` 격리 정책)
- DART 파라미터/status: [[api_spec]] §3.1
- 멱등·인덱스: [[db_schema]] §3.4(`rcept_no` UNIQUE, `idx_disclosures_*`)

## 리스크 / 법적 검토

- **자본시장법**: Stage 1은 **룰 기반 수집만, LLM 없음** → 투자권유·환각 리스크 **없음**. `corp_name`/수치/날짜 원본 보존(변형 금지) 준수(통합기획서 §11.1, [[CLAUDE]] §4).
- **개인정보**: 없음(공시는 공개 정보).
- **운영 리스크 / 엣지 케이스**:
  - **`stocks` 마스터 미시드** → 커버 필터 무력화. **선행 Spec [[stocks-master-seed]]로 분리 완료**. 본 Spec은 stocks 적재 완료를 전제(테스트는 Testcontainers fixture로 stocks 삽입).
  - **DART API 키 미발급** 시 폴링 실패 → `status=020` 분기 + 운영 로그.
  - **폴링 중복(멀티 인스턴스)** → ShedLock 등 분산 락(MVP 단일 인스턴스는 선택, [[feature_structure]] §4 주석).
  - **DART rate limit**: 일 20,000 호출 한도(확인 필요) vs 1분 폴링 1,440/일 → 여유.
  - **윈도우 경계 누락/중복** → `bgn_de~end_de` + `rcept_no` 멱등 이중 방어.
  - **비상장 공시**(`stock_code` NULL) → 정책: skip(커버 외) 또는 NULL 저장. **결정 필요**.
  - **타임아웃/네트워크 실패** → 백오프 재시도, 스케줄러 비중단.
- **DART API 키 발급 완료** → `list.json` 응답·status·rate-limit **실측 검증을 본 Spec 범위에 포함**(권장 방향 8). 결과로 [[api_spec]] §3.1 확정 갱신.

## 권장 구현 방향

1. **DART 클라이언트 = RestClient 확정**(Spring 6.1+ `spring-web` 내장, webflux 미추가·동기 폴링 적합·경량). [[feature_structure]]/[[api_spec]]의 "WebClient" 표기는 RestClient로 정합 갱신 필요(문서 동기화).
2. **범위 = 메타데이터만 확정**: 본 Spec은 `list.json` 메타만 적재(`content_text`·`attachment_url`은 NULL). **본문 추출(document.xml)은 후속 Spec으로 분리**. 유형 분류는 `pblntf_ty`+`report_nm` 룰로 시작.
3. **폴링 윈도우**: 마지막 성공 폴링 시각 보관(인메모리/설정) + `rcept_no` 멱등. 콜드스타트는 당일.
4. **이벤트**: `DisclosureCollectedEvent` 발행(`@TransactionalEventListener(AFTER_COMMIT)` 전제). 리스너는 analysis 도메인 후속이라 **이번엔 발행만**.
5. **엔티티 도입 시작점**: 이 기능에서 `Disclosure` 엔티티+레포 최초 생성, `ddl-auto: validate`로 V4 스키마와 정합 확인.
6. **선행 의존**: `stocks` 시드는 [[stocks-master-seed]]로 분리 완료 — 본 Spec은 적재 완료 전제(테스트는 Testcontainers stocks fixture).
7. **추가 인프라(@EnableScheduling·`dartcommons.dart.*` 설정)는 구현(dc-implement) 단계 작업** — 계획 단계에서 build.gradle/application.yml/앱 설정을 손대지 않음(사용자 결정).
8. **DART 실측 검증(본 Spec 포함)**: 키 발급 완료. RestClient(또는 일회성 호출)로 `list.json`을 실제 호출해 응답 필드·status 코드·페이지네이션·rate-limit을 실측하고 [[api_spec]] §3.1과 본 Spec에 확정 반영. ※ 키는 환경변수/`application-local.yml`로만 주입 — 명령/로그에 노출 금지([[CLAUDE]] §7).

## Tech Review (dc-tech-review · 2026-05-30)

### 아키텍처 분해
- **영향 레이어**: backend(`infrastructure`, `disclosure`, `shared`) — 프론트 영향 없음, LLM 파이프라인(Stage 2~5) 미접촉
- **신규**: `infrastructure/dart`(RestClient·Properties·DTO), `disclosure`(엔티티·레포·수집서비스·유형분류·폴링잡), `shared/event`(이벤트), `@EnableScheduling`
- **수정**: `application.yml`(dart 설정), `DartcommonsApplication`(스케줄링) — 모두 **구현 단계**에서만
- **의존 도메인 규칙**: disclosure→infrastructure는 인터페이스 경유, 이벤트는 shared. 역방향 import 금지([[CLAUDE]] §3-2) 준수

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | DART `list.json` **실측 검증**(응답 필드·status·페이지네이션·rate-limit) | infrastructure(조사) | BE | 하 | 키(발급완료) |
| 2 | `DartApiProperties`(@ConfigurationProperties `dartcommons.dart`) + `application.yml` dart 설정 + `@EnableScheduling` | config | BE | 하 | - |
| 3 | `DartListResponse` 응답 DTO(record, 스키마 파싱) | infrastructure/dart/dto | BE | 하 | #1 |
| 4 | `DartClient`(**RestClient**) — 호출·status 분기·타임아웃·지수 백오프·페이지네이션 | infrastructure/dart | BE | 중 | #2,#3 |
| 5 | `Disclosure` 엔티티 + `DisclosureRepository`(`existsByRceptNo`) — V4 매핑(최초 엔티티) | disclosure | BE | 하 | - |
| 6 | `DisclosureTypeClassifier` — 룰 기반 유형 분류(`pblntf_ty`+`report_nm`) | disclosure/services | BE | 중 | #3 |
| 7 | `DisclosureCollectionService` — 추출→유형분류→커버필터(stocks)→`rcept_no` 멱등 적재 | disclosure/services | BE | 중 | #4,#5,#6,(선행)stocks |
| 8 | `DisclosureCollectedEvent`(shared) + AFTER_COMMIT 발행(리스너는 analysis 후속) | shared/event | BE | 하 | #5,#7 |
| 9 | `DisclosurePollingJob` — `@Scheduled` 1분, 폴링 윈도우·콜드스타트·예외 비중단 | disclosure | BE | 중 | #7 |
| 10 | 통합 테스트(Testcontainers): stocks fixture + 멱등/커버필터/status 분기/페이지네이션 | test | BE | 중 | #7,#9 |
| 11 | 문서 정합: [[feature_structure]]·[[api_spec]] "WebClient"→"RestClient" + 실측 결과 반영 | docs | BE | 하 | #1 |

### DB / 마이그레이션 영향
- **신규 마이그레이션 없음** — `disclosures`(V4)·`stocks`(V2) 기존. `ddl-auto: validate`로 `Disclosure` 엔티티 ↔ V4 정합 확인(불일치 시 부팅 실패가 가드).
- `stocks` 적재는 **선행 Spec [[stocks-master-seed]]** 담당(본 Spec 마이그레이션 무관).

### 외부 계약 영향
- **신규 소비**: DART `list.json`([[api_spec]] §3.1) — 계약 변경이 아닌 신규 호출. 실측(#1)으로 필드·status 확정.
- KRX/카카오/LLM **무관**.

### 리스크 & 법적 검토
- **자본시장법/환각**: Stage 1은 **룰 기반·LLM 없음** → 투자권유·환각 리스크 **없음**. `corp_name`/수치/날짜 원본 보존(변형 금지) 준수([[CLAUDE]] §4).
- **개인정보**: 없음(공시는 공개).
- **운영 리스크**:
  - 멱등: `rcept_no` UNIQUE + `existsByRceptNo` 선검사(중복 발송 1차 방어).
  - 폴링 윈도우 경계 누락/중복 → `bgn_de~end_de` + 멱등 이중 방어. 콜드스타트=당일.
  - 멀티 인스턴스 중복 폴링 → ShedLock **후속**(MVP 단일 인스턴스는 비차단).
  - 키 보안: `DART_API_KEY`는 env/`application-local.yml`만, 명령·로그 노출 금지([[CLAUDE]] §7).
  - **선행 의존**: [[stocks-master-seed]] 미완 시 커버필터 무력화(테스트는 fixture로 우회).
- **확인 필요**: DART `list.json` 실제 응답(#1로 해소 예정), KRX 엔드포인트(선행 Spec).

### 예상 wave 수 — 3 wave
- **wave 1** (infrastructure): #1 실측 → #2 설정/스케줄링 → #3 DTO → #4 DartClient
- **wave 2** (disclosure 도메인): #5 엔티티/레포 → #6 분류기 → #7 수집서비스 → #8 이벤트
- **wave 3** (구동·검증): #9 폴링잡 → #10 통합테스트 → #11 문서 정합
- ※ [[stocks-master-seed]]는 **병렬 선행** — wave 2의 커버필터 실동작 전에 적재 완료 권장(테스트는 fixture로 선행 무관).

### 구현 가능 판단
- ✅ 구현 가능. DB 변경 없음, 외부 계약 명확(실측으로 확정), 법적 리스크 낮음. **Draft → Approved 전환 권장**.
