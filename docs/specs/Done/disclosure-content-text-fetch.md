---
type: spec
status: Done
created: 2026-06-29
updated: 2026-06-29
---

# 공시 본문(content_text) 수집 — DART 문서원문 API Spec

> 상태: Approved → **Done** (2026-06-29, 구현 완료 — SHA 1187ddf)
> 후행 Spec: [[analysis-stage3-rag-chroma]] — 본 Spec이 채우는 `content_text`가 Stage 3 임베딩의 **원천**. Stage 3 임베딩 적재·검색(#8·#12)은 본 Spec 완료에 블로킹됨(A2 결정, 2026-06-29).

## 배경 / 목적

- **문제**: `disclosures.content_text`(공시 본문)가 **항상 null**이다. Stage 1 수집(`DisclosureCollectionService`)은 DART `list.json`의 메타데이터(rcept_no, corp_name, report_nm, rcept_dt, disclosure_type)만 적재하고, 본문은 "후속 Spec"으로 명시 보류됨(`Disclosure.java` L18·L58). 컬럼은 V4에서 `TEXT`로 이미 정의됨.
- **해결**: DART **`document.xml`**(공시 원문) API로 접수번호별 원문을 내려받아 본문 텍스트를 추출, `content_text`에 채운다. 이로써 ① Stage 3 RAG 임베딩 원천 확보 ② Stage 2 프롬프트 본문 섹션 추가 가능(`Stage2PromptBuilder` 주석 확장점) ③ 향후 본문 기반 검색.
- **페르소나/티어**: 직접적 사용자 노출 없는 **인프라/내부 데이터 확장**. Stage 3(Pro+) 가치를 가능케 하는 선결 작업. **본문 원문 자체는 API로 사용자에게 노출하지 않음** — 대용량 원문은 `attachment_url`(DART 원문 링크)로 제공([[api_spec]] §2.3).
- **결정 근거**: [[analysis-stage3-rag-chroma]] Tech Review(2026-06-29) — 임베딩 품질 우선(A2). 제목(`report_nm`) 단문 임베딩(A1) 대신 본문 확보를 선택.

## 요구사항

### 기능 요구사항
- [ ] **R1. DART 문서원문 클라이언트**: `infrastructure/dart/DartDocumentClient.java` — `GET /document.xml?crtfc_key&rcept_no` 호출. **zip 응답** 처리 + **타임아웃·@Retryable·HostWhitelist**는 기존 `DartCorpCodeClient`(zip 매직넘버 0x50 0x4B + `ZipInputStream` + StAX) / `DartClient`(@Retryable 2~30초 backoff) 패턴 답습.
- [ ] **R2. 본문 텍스트 추출**: zip 내 DART 마크업 XML → **본문 평문 텍스트** 추출(태그 제거, 표/섹션 처리). 인코딩(EUC-KR/MS949 가능성) 안전 처리. DART 원본 수치·날짜·회사명은 **변형 금지**(CLAUDE.md §4) — 추출은 룰 기반(Stage 1 성격), LLM 미사용.
- [ ] **R3. 본문 채움 — 신규 공시**: 신규 수집된 공시에 대해 본문을 fetch해 `content_text` UPDATE. 폴링 SLO(1분) 보호를 위해 **수집 트랜잭션과 분리한 비동기 경로**(아래 권장 방향 — 신규 이벤트 리스너 vs 별도 배치).
- [ ] **R4. 본문 채움 — 과거 백필**: `content_text IS NULL`인 기존 공시(최대 93k)를 대상으로 본문 일괄 fetch. **DART 일일 호출 한도** 내 청크/스로틀링(아래 리스크). `scripts/data_collection/` Python 또는 BE 비동기 잡(`BackfillJobService` 패턴) 중 택1(권장 방향 참조).
- [ ] **R5. 멱등·재시도 안전**: 이미 본문이 채워진 공시는 재fetch 스킵. 부분 실패(한 건 fetch 실패)가 전체 배치를 중단시키지 않음. fetch 실패 건은 재시도 대상으로 남김.
- [ ] **R6. 본문 크기 가드**: 초대형 원문은 임베딩·저장 비용 과다 → **상한(예: N KB) 초과 시 truncate + 원문은 `attachment_url`로 참조**. 상한값은 `content_text` 길이 분포 측정 후 확정(확인 필요).

### 비기능 요구사항
- [ ] **R7. 격리**: disclosure 도메인은 DART SDK/HTTP에 직접 의존 금지 → `infrastructure/dart/` 경유(CLAUDE.md §3-2). 기존 `DartClient`/`DartCorpCodeClient`와 동일 위치.
- [ ] **R8. 외부 호출 가드**: `document.xml` 호출에 타임아웃·지수 백오프 재시도(`DartClient` 선례). zip 비정상/빈 응답/파싱 실패는 명확히 분기 + 로깅(본문 없음으로 처리, 전체 중단 금지).
- [ ] **R9. 테스트**: zip 파싱·텍스트 추출 단위 테스트(고정 zip 픽스처). 통합 테스트는 Testcontainers PostgreSQL로 `content_text` UPDATE 검증. 실 DART 호출은 통합 테스트 제외(외부 의존).
- [ ] **R10. 데이터 보호**: 본문 대량 적재 시에도 PostgreSQL bind mount + 백업 정책 유지([[feedback_data_protection]] — 2026-06-04 손실 사고 교훈). 백필은 분할 커밋으로 진행률 추적.

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(infrastructure/dart, disclosure) · (선택) scripts(data_collection — 백필) · backend/test
- **신규 파일 (예상)**:
  - `backend/.../infrastructure/dart/DartDocumentClient.java` — document.xml 호출 + zip/XML 본문 추출
  - `backend/.../infrastructure/dart/DartDocumentParser.java`(또는 클라이언트 내부) — 마크업 XML → 평문 텍스트
  - `backend/.../disclosure/services/DisclosureContentService.java`(가칭) — 본문 fetch + content_text UPDATE 오케스트레이션(멱등·실패 격리)
  - (택1) `DisclosureContentBackfillService.java` 또는 `scripts/data_collection/fetch_content.py` — 과거 93k 백필
  - (R3 비동기 채택 시) 신규 이벤트 리스너 — `DisclosureCollectedEvent` 구독(AnalysisOrchestrator와 별개 컴포넌트)
- **수정 파일 (예상)**:
  - `application.yml` — `document.xml` 타임아웃이 list.json보다 길 수 있음(원문 zip 대용량) → `dartcommons.dart.document-timeout-ms` 등 분리 검토
  - (R6 상태추적 채택 시) `Disclosure.java` — `contentFetchedAt` 필드 추가
- **DB 변경**:
  - `content_text` 컬럼은 **V4에 이미 존재(TEXT)** → 채움만 하면 DDL 불요.
  - **단, fetch 상태 추적 방식 결정 필요**(아래): `content_text IS NULL` 센티넬(마이그레이션 0) vs `content_fetched_at TIMESTAMPTZ` 신규 컬럼(**V24 마이그레이션 필요** — 최신은 V23). 후자는 "미fetch" vs "fetch했으나 본문 없음"을 구분 가능 + 백필 타겟팅 신뢰성↑.
- **외부 계약**: DART `document.xml`(신규 소비) — [[api_spec]] §3.1에 "공시 원문 / document.xml / crtfc_key,rcept_no / 신규 공시 시" 이미 명문화됨. list.json/corpCode.xml 계약 변경 없음.

## 관련 패턴 / 과거 사례

- **과거 사례(Step 0)**: `docs/solutions/` 디렉터리 없음 — 관련 해결책 문서 없음.
- **직접 참조 가능 자산(코드 확인 완료)**:
  - `infrastructure/dart/DartCorpCodeClient.java` — **zip 응답 처리 본보기**: 매직넘버 검증(L75 `0x50/0x4B`), `ZipInputStream`(L81), StAX `XMLStreamReader`(L101~) + `IS_NAMESPACE_AWARE=false`/`SUPPORT_DTD=false`. `document.xml`도 zip+XML이므로 그대로 응용.
  - `infrastructure/dart/DartClient.java` / `DartPageFetcher.java` — @Retryable(max 3, backoff 2~30s), `HttpClient` 타임아웃, `HostWhitelist.verify`(SSRF 차단), AOP 프록시 우회용 fetcher 분리 패턴.
  - `disclosure/services/DisclosureCollectionService.java` — 멱등 체크(`existsByRceptNo`) + `DataIntegrityViolationException` 동시성 처리 + `DisclosureCollectedEvent` 발행(L105) 패턴.
  - `disclosure/services/BackfillJobService.java` + `entities/BackfillJob.java`(V12) — **비동기 백필 잡 오케스트레이션**(PENDING→RUNNING→SUCCEEDED/FAILED, 진행률 갱신) — R4 백필을 BE로 구현 시 재사용.
  - `analysis/services/AnalysisOrchestrator.java` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` 리스너 — R3 비동기 본문 fetch 리스너의 본보기.
  - `analysis/services/Stage2PromptBuilder.java` — 주석 "본문(content_text) 추출 후속 Spec에서는 본 빌더에 본문 섹션 추가" — 본문 확보 후 Stage 2 프롬프트 강화 확장점(본 Spec 범위 밖, 후속).

## 리스크 / 법적 검토

- **[DART 일일 호출 한도 — 핵심 운영 제약]** `document.xml`은 접수번호당 1회 호출. **과거 93k 백필 = 93k 호출** → DART 일일 쿼터(키별 한도)에 종속. **다일 분할 + 스로틀링** 필요. 신규 공시 fetch는 1분당 소량이라 무방하나, 백필은 일일 한도/우선순위(커버 종목·최근순) 전략 필수. → 한도 실측 후 백필 일정 산정(확인 필요).
- **[본문 추출 품질]** DART 원문 XML은 표·섹션·첨부가 섞인 독자 마크업. 평문 추출 시 표 구조 손실·노이즈 가능 → 임베딩 품질에 영향. 시범 표본으로 추출 결과 검수 필요(후행 Stage 3 정확도와 연결).
- **[인코딩]** 구 공시 원문은 EUC-KR/MS949 가능성 → 디코딩 오류 시 깨진 텍스트 저장 위험. Charset 안전 처리 + 검증.
- **[저작권/이용약관]** DART 공시는 공개 데이터이나 **원문 전문(全文)의 외부 재공개는 이용약관 경계**. 본 Spec은 `content_text`를 **내부 처리(임베딩·분석)용으로만 저장**, 사용자에게는 `attachment_url`(DART 원문 링크)로 안내 — 전문 노출 금지([[api_spec]] §2.3 정합).
- **[원본 불변]** 회사명·수치·날짜 등 인용 필드는 DART 원본 그대로(CLAUDE.md §4). 본문 추출은 **룰 기반·LLM 미개입** — 추출 단계에서 변형/요약 금지.
- **[데이터 보호]** 93k 본문(대용량 TEXT) 적재로 DB 용량 증가 → bind mount + 백업 유지([[feedback_data_protection]]). 백필은 분할 커밋(`BackfillJob` 진행률)으로 손실 구간 최소화.
- **[폴링 SLO]** 신규 공시 본문 fetch를 수집 동기 경로에 넣으면 1분 폴링 SLO 위협 → **비동기 분리 필수**(R3).

## 권장 구현 방향

### 본문 fetch 트리거 (R3/R4)
- **권장: 신규 = 비동기 이벤트 리스너 / 과거 = 별도 백필 잡 (2-track)**
  - **신규**: `DisclosureCollectedEvent`를 구독하는 **신규 리스너**(AnalysisOrchestrator와 별개 `@Async` 컴포넌트)가 본문 fetch → `content_text` UPDATE. 수집 트랜잭션·Stage 2 분석과 독립 → 폴링 SLO·분석 큐 무영향. (Stage 2는 본문 없이도 동작하므로 순서 의존 없음.)
  - **과거**: `BackfillJobService` 패턴의 BE 비동기 잡 또는 `scripts/data_collection/` Python. **BE 잡 권장**(진행률 추적·재시도·DART 클라이언트 재사용 일관). DART 일일 한도 스로틀링 내장.

### fetch 상태 추적 (DB)
- **권장: `content_fetched_at TIMESTAMPTZ` 신규 컬럼 추가(V24 마이그레이션)**
  - 장점: "미fetch(null)" vs "fetch 완료(본문 없음 포함)" 구분 → 백필 타겟팅 정확, 빈 본문 무한 재시도 방지.
  - 대안(`content_text IS NULL` 센티넬, 마이그레이션 0): 간단하나 "본문 없는 공시"를 영구 미fetch로 오인 → 매 백필 재호출. **비권장**.

### 본문 추출 깊이 (R2)
- 1차: 태그 제거 + 섹션/문단 평문화(표는 셀 텍스트 직렬화). 노이즈 최소화 우선, 완벽한 구조 보존은 후순위(임베딩 입력 목적).

### 범위 경계 (1차)
- **포함**: DartDocumentClient(R1) · 본문 추출(R2) · 신규 비동기 채움(R3) · 과거 백필 경로(R4, 시범 표본 우선) · 멱등/실패격리(R5) · 크기 가드(R6) · 격리/가드/테스트(R7~R10) · `content_fetched_at` 마이그레이션.
- **제외(후속 분리)**: ① Stage 2 프롬프트에 본문 섹션 추가(`Stage2PromptBuilder` 강화) ② `content_text` 임베딩(=[[analysis-stage3-rag-chroma]]) ③ 93k 전건 백필 완주(시범 표본 검수 후 별도 운영) ④ 사용자 대상 본문 노출 UI.

### 확인 필요 (Tech Review/구현 전 결정)
1. fetch 상태 추적 — `content_fetched_at` 컬럼(V24) vs `content_text IS NULL` 센티넬.
2. 백필 실행 주체 — BE 비동기 잡(`BackfillJobService`) vs `scripts/data_collection/` Python.
3. DART `document.xml` 일일 호출 한도 실측 → 93k 백필 일정·스로틀링 파라미터.
4. 본문 크기 상한(truncate 기준) — `content_text` 길이 분포 측정 후 확정.
5. 본문 추출 깊이 — 평문화 수준(표 직렬화 방식), 시범 표본 검수 기준.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-29)

> 검토 방식: Spec의 코드 참조 주장을 실제 소스로 전수 대조(추정 배제). 아래 모두 코드 확인 완료.

### 검증된 Spec 주장 (코드 대조 결과)
- ✅ `DartCorpCodeClient.java` L32 주석 **"본문 추출(document.xml) 클라이언트는 후속 Spec"** — 본 작업이 의도된 다음 단계로 명시됨. `document.xml` 호출 코드는 전무(`grep` 결과 무) → **신규 구현 확정**.
- ✅ `DartCorpCodeClient`의 zip 처리: 매직넘버 `0x50/0x4B`(L75) + `ZipInputStream`(L81) + StAX `XMLStreamReader`(L101, `IS_NAMESPACE_AWARE=false`/`SUPPORT_DTD=false`) → `DartDocumentClient`의 본보기로 그대로 응용 가능.
- ✅ `content_text` 컬럼 = **V4 `TEXT`로 이미 존재** → 채움만 하면 컬럼 DDL 불요. fetch 상태 추적 컬럼은 부재(최신 마이그레이션 **V23** 확인) → `content_fetched_at` 추가 시 **V24** 신규.
- ✅ `BackfillJob`(V12) `Status enum {PENDING,RUNNING,SUCCEEDED,FAILED}` + `BackfillJobService` 비동기 잡 패턴 존재 → R4 백필을 BE 잡으로 구현 시 재사용.
- ✅ `ExecutorConfig.java` — `analysisExecutor`(폴링 SLO 보호) + backfill executor **분리 운영 중**. 빈명 매칭 실패 시 `SimpleAsyncTaskExecutor` fallback. → 본문 fetch는 `analysisExecutor` **오염 금지**, 신규 풀 필요.
- ✅ `Stage2PromptBuilder.java` L15 — "본문(content_text) 추출 후속 Spec에서는 본 빌더에 본문 섹션 추가" 확장점 명시(본 Spec 범위 밖, 후속).
- ✅ `api_spec.md` §3.1 — `document.xml / crtfc_key,rcept_no / 신규 공시 시` 이미 계약 명문화. §2.3 — 대용량 원문은 `attachment_url`로 제공(전문 비노출 정합).

### 🔑 선결 결정 (구현 전 확정 — Spec "확인 필요" 대응)
1. **fetch 상태 추적** → **권장: `content_fetched_at TIMESTAMPTZ`(V24) 채택.** 부분 인덱스 `WHERE content_fetched_at IS NULL`로 백필 타겟팅. (`content_text IS NULL` 센티넬은 "본문 없는 공시" 무한 재시도 → 비권장.)
2. **백필 주체** → **권장: BE 비동기 잡(`BackfillJobService` 패턴).** DART 클라이언트·진행률·재시도 일관. (Python `scripts/`는 DART 클라이언트 중복 구현 부담.)
3. **DART `document.xml` 일일 호출 한도** → **실측 필요(확인 필요).** 93k 백필 일정·스로틀 파라미터 종속. ← 구현 착수는 가능하나 백필 완주 일정은 이 값에 종속.
4. **본문 크기 상한(truncate)** → `content_text` 길이 분포 측정 후 확정(시범 표본). 미확정 상태로도 Wave 1 클라이언트/파서 구현 가능.

### 아키텍처 분해
- **영향 레이어**: backend(infrastructure/dart, disclosure, shared/config) · backend/db(Flyway) · backend/test. (scripts는 BE 잡 채택 시 불요.)
- **신규**: `infrastructure/dart/{DartDocumentClient, DartDocumentParser}` · `disclosure/services/{DisclosureContentService, DisclosureContentBackfillService}` · `disclosure/` 신규 이벤트 리스너(`DisclosureCollectedEvent` 구독) · `shared/config/ExecutorConfig`에 `contentFetchExecutor` 빈 · `db/migration/V24__add_content_fetched_at_to_disclosures.sql`.
- **수정**: `Disclosure.java`(contentFetchedAt 필드 + 채움 메서드) · `application.yml`(document 타임아웃/크기상한/스로틀) · (참조) `DartApiProperties`.
- **Stage 1~5 영향**: Stage 1 수집 트랜잭션·Stage 2 분석은 **본문 없이도 동작** → 순서 의존 없음. 본문 fetch는 완전 비동기 부가 경로. Stage 3는 본 Spec 산출물을 소비(후행).

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `DartDocumentClient` — document.xml 호출 + zip 처리(매직넘버·ZipInputStream) + HostWhitelist + @Retryable·타임아웃(`DartCorpCodeClient`/`DartClient` 답습) | backend/infra/dart | BE | 중 | - |
| 2 | `DartDocumentParser` — DART 마크업 XML → 평문(태그제거·표 직렬화·**EUC-KR/MS949 인코딩** 안전 처리) | backend/infra/dart | BE | 상 | #1 |
| 3 | `V24__add_content_fetched_at_to_disclosures.sql` — `content_fetched_at TIMESTAMPTZ` + 부분 인덱스(`WHERE content_fetched_at IS NULL`) | backend/db | BE | 하 | - |
| 4 | `Disclosure` 엔티티 `contentFetchedAt` 필드 + 본문 채움 메서드(불변식 유지) | backend/disclosure/entities | BE | 하 | #3 |
| 5 | `ExecutorConfig` `contentFetchExecutor` 빈 추가(analysisExecutor 격리, AbortPolicy/CallerRuns 결정) | backend/shared/config | BE | 하 | - |
| 6 | `DisclosureContentService` — fetch→평문→`content_text`/`content_fetched_at` UPDATE, **멱등**(이미 fetch 스킵)·실패격리·**크기 가드(R6)** | backend/disclosure/services | BE | 중 | #2,#4 |
| 7 | 신규 비동기 리스너 — `DisclosureCollectedEvent` 구독(`@TransactionalEventListener(AFTER_COMMIT)`+`@Async("contentFetchExecutor")`) → `DisclosureContentService` | backend/disclosure | BE | 중 | #5,#6 |
| 8 | `DisclosureContentBackfillService` — `content_fetched_at IS NULL` 대상 백필 잡(`BackfillJob` 패턴, **DART 일일한도 스로틀**·진행률·재시도) | backend/disclosure/services | BE | 중 | #6 |
| 9 | `application.yml` — `dartcommons.dart.document-timeout-ms`·본문 크기 상한·백필 스로틀 설정 블록 | backend | BE | 하 | #1 |
| 10 | 단위 테스트(고정 zip 픽스처 파싱/추출) + Testcontainers `content_text`/`content_fetched_at` UPDATE 검증(R9) | backend/test | BE | 중 | #2,#6 |

### DB / 마이그레이션 영향
- **신규 마이그레이션 1건**: `V24__add_content_fetched_at_to_disclosures.sql` — `ALTER TABLE disclosures ADD COLUMN content_fetched_at TIMESTAMPTZ;` + `CREATE INDEX idx_disclosures_content_pending ON disclosures (rcept_dt DESC) WHERE content_fetched_at IS NULL;`(백필 타겟팅·미fetch 우선순위).
- `content_text`(TEXT)는 V4 기존 컬럼 재사용 → 변경 없음. V24는 V23 다음 순번(불변 규칙 §6-3 준수).
- 대용량 TEXT 적재 → DB 용량 증가(데이터 보호 정책 유지).

### 외부 계약 영향
- **DART `document.xml` 신규 소비** — zip(원문 XML) 응답. list.json/corpCode.xml 계약 변경 없음. KRX/카카오/LLM 무관.
- 응답 비정상(빈 zip/매직넘버 불일치/파싱 실패)은 "본문 없음"으로 분기 + `content_fetched_at`만 기록(무한 재시도 방지) 또는 재시도 큐 — #6 정책 확정.

### 리스크 & 법적 검토
- **[DART 일일 호출 한도 — 핵심]** 93k 백필=93k 호출 → 키별 일일 쿼터 종속. 다일 분할·스로틀·우선순위(커버종목·최근순) 필수. **한도 실측 전 대량 백필 금지**(시범 표본 우선).
- **[본문 추출 품질·인코딩]** DART 독자 마크업/표/EUC-KR → 추출 노이즈·디코딩 깨짐 위험. 시범 표본 검수가 후행 Stage 3 임베딩 품질과 직결.
- **[저작권/이용약관]** 원문 전문은 **내부 임베딩·분석용으로만 저장**, 사용자엔 `attachment_url`만 노출(전문 비공개 — api_spec §2.3 정합).
- **[원본 불변 — CLAUDE.md §4]** 본문 추출은 **룰 기반·LLM 미개입**. 회사명·수치·날짜 변형/요약 금지.
- **[폴링 SLO]** 본문 fetch는 `contentFetchExecutor`로 완전 분리 — `analysisExecutor`/수집 트랜잭션 무영향(필수).
- **[자본시장법]** 본 Spec은 본문 텍스트 적재만 — 투자 권유 표현 무관(노출은 후행 FE/분석 Spec 책임).

### 예상 wave 수
- **Wave 1 (클라이언트·추출, DB 무관)**: #1 #2 #9 #10(파서 단위) — `DartDocumentClient`+`DartDocumentParser`+설정. 독립 머지, 시범 표본으로 추출 품질·크기분포·인코딩 검수.
- **Wave 2 (채움·신규 공시)**: #3 #4 #5 #6 #7 — V24 마이그레이션 + `DisclosureContentService` + 비동기 리스너. 완료 시 **신규 공시는 자동으로 본문 확보** → [[analysis-stage3-rag-chroma]] Wave 2 착수 가능.
- **Wave 3 (과거 백필)**: #8 + #10(통합) — 백필 잡 + 시범 표본 후 단계적 확대. DART 한도 실측 종속.

### Stage 3 연계
- **Wave 2 완료가 [[analysis-stage3-rag-chroma]] 임베딩 착수의 최소 선결**(신규 공시 본문 확보). 93k 전건은 Wave 3 백필과 Stage 3 백필(#12)이 순차 연동.
