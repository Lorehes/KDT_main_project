---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-10
---

# BE API 블로킹 버그 일괄 픽스 Spec

> 상태: **Approved** (2026-06-10, dc-tech-review 승인)

## 배경 / 목적

`FE-BE정합성수정` 커밋(2026-06-09)으로 공시·분석·알림 REST API가 신규 구현됐으나, 두 차례 코드 리뷰에서 **현 시점에 동작 자체가 막혀 있는 P0 6건**이 확인됐다. 본 Spec은 이 6건을 단일 PR로 일괄 픽스해 MVP 라우트(공시 피드, 종목 등록, Premium 응답)를 정상화한다.

- **현황**: `scope=all` 피드, 종목 등록, Premium `financial_context`, 공시 정렬, Tier 추출이 잘못 동작
- **목표**: 6건 모두 통합 테스트(Testcontainers)와 함께 수정
- **BM 연관**: Free/Pro/Premium 전 티어 공통 — 현재 모든 사용자가 영향을 받음

---

## 요구사항

- [ ] **R1** `DisclosureRepository.findFiltered()` 의 JPQL `:stockCodes IS NULL` 분기 제거 — Hibernate 6 Collection 파라미터 IS NULL 미지원 ([CLAUDE.md §3-2](../../CLAUDE.md)). 서비스 계층에서 `stockCodes == null` 시 별도 `findAll(Pageable)` 또는 QueryDSL 동적 조건으로 분기
- [ ] **R2** JPQL `findFiltered()` 끝에 `ORDER BY d.rceptDt DESC, d.id DESC` 명시 — Spring Data JPA Sort 자동 적용 미신뢰. 동일 날짜 내 결정론적 순서 보장
- [ ] **R3** `DisclosureQueryService.list()` 에서 `ids.isEmpty()` 가드 후 `AnalysisResultRepository.findByDisclosureIdIn(...)` 호출 — 빈 컬렉션 `IN ()` 구문 오류 방지
- [ ] **R4** `PortfolioRequest.avgBuyPrice` / `quantity` 의 `@NotNull` 제거 — FE `portfolios/new/page.tsx` 가 두 필드를 optional 로 전송. 서비스 계층에서 null 체크 후 AES 암호화 분기
- [ ] **R5** `AnalysisResponse.from()` L93 의 `premium ? null : null` 오류 수정 — `premium ? ar.getFinancialContext() : null` 로 교체(Stage 5 미구현 상태이면 `null` 단일 리터럴 + `/* TODO Stage-5 */` 명시)
- [ ] **R6** `DisclosureController.extractTier()` Authority 추출 우선순위 수정 — `findFirst()` 대신 `PREMIUM > PRO > FREE` 우선순위로 최고 티어 선택. 또는 JWT 클레임 `tier` 단일 클레임 도입
- [ ] **R7** 위 6건 통합 테스트 추가 — `DisclosureControllerTest`, `AnalysisResponseTest`, `PortfolioRequestValidationTest` (Testcontainers + MockMvc)

---

## 영향 범위

- **영향 레이어**: backend (disclosure / analysis / user 도메인)
- **DB 변경**: 없음
- **외부 계약**: 없음 — API 경로/응답 스키마 변경 없음, 동작만 정상화

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../disclosure/repositories/DisclosureRepository.java` | R1·R2: JPQL Collection null 분기 제거 + ORDER BY 명시 |
| `backend/.../disclosure/services/DisclosureQueryService.java` | R1·R3: `stockCodes == null` 분기 처리 + `ids.isEmpty()` 가드 |
| `backend/.../user/dto/PortfolioRequest.java` | R4: avgBuyPrice/quantity `@NotNull` 제거 |
| `backend/.../user/services/PortfolioService.java` | R4: null 체크 후 암호화 분기 |
| `backend/.../analysis/dto/AnalysisResponse.java` | R5: financial_context 삼항 연산자 수정 |
| `backend/.../disclosure/controllers/DisclosureController.java` | R6: extractTier 우선순위 수정 |
| `backend/src/test/.../disclosure/DisclosureControllerTest.java` (신규) | R7 |
| `backend/src/test/.../analysis/AnalysisResponseTest.java` (신규) | R7 |
| `backend/src/test/.../user/PortfolioRequestValidationTest.java` (신규) | R7 |

---

## 관련 패턴 / 과거 사례

- `disclosure-collection-pipeline` (Done) — DisclosureRepository.existsByRceptNo 멱등 가드 패턴
- `analysis-stage2-llm` (Done) — `AnalysisResponse` `@JsonInclude(NON_NULL)` 티어 차등 패턴
- `user-auth-jwt-oauth2` (Done) — Tier 추출 SecurityContext 패턴
- 2026-06-08 `notification-retry-job` Wave 1 리뷰 게이트 사례 — P0 일괄 픽스 + 통합 테스트 동시 추가 선례

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| `@NotNull` 제거로 null 매수가/수량이 DB 에 그대로 흘러갈 수 있음 | 서비스 계층에서 명시적 null 분기 — null 이면 암호화 컬럼에 NULL 저장 |
| ORDER BY 추가가 기존 인덱스(idx_disclosures_corp/stock) 와 충돌 | `(stock_code, rcept_dt DESC)` 복합 인덱스를 후속 Spec(`performance-caching-staletime`) 에서 검토 — 본 Spec 범위 밖 |
| Tier 추출 변경으로 기존 PRO/Premium 사용자 응답 변화 | Wave 종료 전 회귀 테스트 — 각 티어별 JWT 로 응답 필드 노출 화이트리스트 검증 |
| Stage 5 미구현 상태에서 `financial_context` 라인 변경 | `null` 리터럴 + 주석으로 의도 명시. Stage 5 Spec 진입 시 본 라인을 수정 포인트로 등재 |

---

## 권장 구현 방향

- 단일 PR 로 6건 + 테스트 동시 머지 (R1·R2 → R3 → R4·R5 → R6 순)
- 각 R 마다 실패 재현 테스트 → 픽스 → 통과 순으로 진행 (TDD)
- `extractTier()` 는 `shared/security/SecurityUtils` 로 추출하는 리팩토링이 [[architecture-refactoring-cleanup]] 에서 다뤄지므로, 본 Spec 에서는 로직만 수정하고 위치 이동은 후속 Spec 에 위임
- `findFiltered()` 의 sentiment 필터 메모리 처리 이슈는 별도 Spec [[fe-correctness-investor-protection]] 에서 PageMeta 정합성과 함께 해결

---

## Tech Review (dc-tech-review · 2026-06-10)

### 아키텍처 분해

- **영향 레이어**: backend 단독 — `disclosure`(repository·service·controller), `analysis`(dto), `user`(dto·service). FE 변경 없음.
- **신규**: 테스트 3종(`DisclosureControllerTest`, `AnalysisResponseTest`, `PortfolioRequestValidationTest`).
- **수정**: 6개 클래스 — `DisclosureRepository`, `DisclosureQueryService`, `DisclosureController`, `AnalysisResponse`, `PortfolioRequest`, `PortfolioService`.
- **아키텍처 정합**:
  - CLAUDE.md §3-2 — disclosure → user(PortfolioRepository) 직접 의존은 본 Spec 범위 밖 ([[architecture-refactoring-cleanup]] R4 위임).
  - CLAUDE.md §6-3 — Flyway 마이그레이션 무 (DB 컬럼 변경 없음).
  - R5는 `AnalysisResult.getFinancialContext()` 메서드가 현재 존재하지 않음을 확인 — Stage 5 미구현 상태. 단순히 `null` 리터럴 + `/* TODO Stage-5 */` 주석으로 dead code 의도 명시.
  - R4는 DB(V3)에 `avg_buy_price_enc`, `quantity_enc` 모두 이미 nullable + 엔티티도 nullable — Flyway 변경 없이 DTO `@NotNull` 제거 + 서비스 null 분기로 완결.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `DisclosureRepository.findFiltered` 를 stockCodes 유무로 2개 메서드로 분리 (`findFilteredByStocks` / `findAllFiltered`) — JPQL `:stockCodes IS NULL` 제거. JPQL 끝에 `ORDER BY d.rceptDt DESC, d.id DESC` 명시 (R1·R2) | backend/disclosure | BE | 중 | - |
| 2 | `DisclosureQueryService.list()` 에서 stockCodes null 분기 + `ids.isEmpty()` 가드 (R1·R3) | backend/disclosure | BE | 하 | #1 |
| 3 | `PortfolioRequest` `avgBuyPrice`/`quantity` 의 `@NotNull` 제거. `@DecimalMin`·`@Positive` 는 null 통과하도록 유지 (R4) | backend/user | BE | 하 | - |
| 4 | `PortfolioService.createPortfolio()`/`updatePortfolio()` 에 null 가드 → null 이면 `null` byte[] 저장, 비-null 이면 AES 암호화 (R4) | backend/user | BE | 중 | #3 |
| 5 | `AnalysisResponse.from()` L93 `premium ? null : null` 을 단일 `null` + `/* TODO Stage-5 */` 주석으로 교체 (R5) | backend/analysis | BE | 하 | - |
| 6 | `DisclosureController.extractTier()` 우선순위 변경 — Authority 전체를 `Tier` 로 매핑한 뒤 `Stream.max(Comparator.comparingInt(Tier::ordinal))` 또는 PREMIUM > PRO > FREE 명시적 순회로 최고 티어 반환 (R6) | backend/disclosure | BE | 중 | - |
| 7 | `DisclosureControllerTest` 신규 (Testcontainers + MockMvc) — scope=all/portfolio, 빈 포트폴리오, ORDER BY 검증, Tier 회귀(JWT로 PREMIUM·PRO·FREE 응답 필드 화이트리스트) (R7) | backend/test | BE | 중 | #2·#6 |
| 8 | `AnalysisResponseTest` 신규 (단위) — `from(ar, FREE/PRO/PREMIUM)` 각 티어별 `expected_reaction`/`rationale`/`similar_disclosures`/`financial_context` 노출 여부 화이트리스트 + `disclaimer` 상시 포함 (R7) | backend/test | BE | 하 | #5 |
| 9 | `PortfolioRequestValidationTest` 신규 (단위) — `avgBuyPrice=null`/`quantity=null` 통과 + `stockCode=null` 실패 + `@DecimalMin` 경계 검증 (R7) | backend/test | BE | 하 | #3 |

### DB / 마이그레이션 영향

- **Flyway 마이그레이션 불필요** — `V3__create_portfolios.sql` 의 `avg_buy_price_enc`/`quantity_enc` 가 이미 nullable BYTEA. 주석에도 "매수가(선택)", "수량(선택)" 명시.
- **인덱스 변경 무** — R2 ORDER BY 추가가 기존 `idx_disclosures_corp`·`idx_disclosures_stock` 인덱스를 활용 가능. `(stock_code, rcept_dt DESC)` 복합 인덱스는 [[performance-caching-staletime]] R10 으로 위임.
- **JPQL Sort 자동 적용 검증 필요** — Spring Data JPA 가 `@Query` JPQL 에 Pageable Sort 를 자동 부가하는지는 dialect/버전 의존. R1 에서 JPQL 끝에 명시적 `ORDER BY` 를 두면 안전하지만, 동시에 `Pageable.unsorted()` 로 호출해야 충돌 회피. 서비스 계층 호출부도 함께 수정.

### 외부 계약 영향

- 없음 — `api_spec.md §2.3`(공시 목록), `§2.4`(분석 결과), `§2.2`(포트폴리오 등록) 응답 스키마 동일 유지.
- DART/KRX/카카오 알림톡/LLM 프롬프트 변경 없음.
- **JWT Authority 페이로드 확인 필요** — R6 수정 후 `AuthService` 가 발급하는 JWT 의 authorities 가 `ROLE_FREE`/`ROLE_PRO`/`ROLE_PREMIUM` 단일 항목인지 다중 항목인지 검증. 다중 항목이면 우선순위 매핑이 즉시 효과, 단일 항목이면 `findFirst()` 와 동치 동작이지만 안전성 보강.

### 리스크 & 법적 검토

| 리스크 | 영역 | 대응 |
|------|------|------|
| ORDER BY 명시 후 `Pageable.getSort()` 와 JPQL ORDER BY 의 이중 적용 → Hibernate 가 `... ORDER BY rcept_dt DESC, id DESC, rcept_dt DESC` 같은 형태로 SQL 생성 | 기술 | 서비스에서 `PageRequest.of(page, size)` 만 사용 (Sort 제거). JPQL 의 ORDER BY 가 단독 정렬. 통합 테스트로 SQL 로그 확인 |
| R4 null 매수가/수량이 손익 계산 화면에 NPE 유발 | 기술 | `PortfolioResponse` 가 이미 `decryptedPrice != null` 가드. 손익 계산 화면 ([[fe-correctness-investor-protection]]) 에서도 null 표시 처리 — 본 Spec 머지 후 FE 회귀 점검 |
| Tier 추출 변경으로 기존 사용자 응답 변화 | 보안·BM | 통합 테스트에서 PREMIUM JWT → financial_context 노출 안 됨(Stage 5 미구현이므로 모든 티어에서 null) 검증. PRO JWT → expected_reaction/rationale 노출. FREE JWT → 두 필드 모두 미노출 |
| `premium ? null : null` 수정 시 Stage 5 진입 포인트 망각 | 기술 | `/* TODO Stage-5 */` 주석 + [[performance-caching-staletime]] R3(`@CacheEvict` on analyze 완료) 와 후속 Stage 5 Spec 의 인터페이스 정렬 메모 |
| 통합 테스트 추가로 CI 시간 증가 | 운영 | Testcontainers 재사용 (`@Testcontainers(disabledWithoutDocker=true)` + 기존 `TestcontainersConfiguration`). 신규 테스트는 기존 79개 베이스라인에 추가 |
| 자본시장법 §11.1 — disclaimer/면책 문구 회귀 가능성 | 법적 | `AnalysisResponseTest` 에서 모든 티어 응답에 `DISCLAIMER` + `report_inaccuracy_path` 필수 포함 검증 — 회귀 게이트 |

### 예상 wave 수

- **단일 Wave (1 PR) 권장** — 6건 모두 서로 다른 파일이며 의존성 그래프가 얕음. Spec 자체가 "블로킹 버그 일괄 픽스" 목적이므로 분할하면 머지 게이트가 의미를 잃음.
- 작업 카드 #1 → #2 → #3·#5·#6 병렬 → #4 → #7·#8·#9 병렬 (TDD: 각 카드는 실패 재현 테스트 → 픽스 → 통과)
- 베이스라인 79개 + 신규 ~12개 케이스 (Controller 6 + Response 3 + Validation 3) → 총 ~91개 통과를 머지 게이트로 설정.

### 구현 진입 권장 조건

- ✅ Spec Approved 상태 진입 가능 — 작업 카드 9개 명확, DB 변경 없음, 외부 계약 변경 없음, 법적 리스크 disclaimer 회귀로 한정
- 후속 의존: [[architecture-refactoring-cleanup]] R3 (`SecurityUtils.extractTier` 추출) 이 본 Spec 의 R6 로직을 그대로 옮겨가므로, 본 Spec 머지 후 즉시 후속 진입 가능.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
