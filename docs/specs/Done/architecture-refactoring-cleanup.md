---
type: spec
status: Done
created: 2026-06-09
updated: 2026-06-10
---

# 아키텍처 · 유지보수성 정리 Spec

> 상태: **Done** (2026-06-10, 구현 완료 — R1~R18 전체(R10 skip), 테스트 120건 통과)

## 배경 / 목적

`FE-BE정합성수정` 커밋에 BE/FE 신규 파일 다수가 추가되면서, **CLAUDE.md §3-2(도메인 모듈 표준) 위반 + Google Java Style 위반 + FE 중복 코드 5종**이 누적됐다. 본 Spec 은 아키텍처/네이밍/중복 정리를 일괄 처리해 유지보수 부담을 낮춘다.

- **현황**: DTO 가 `services/` 패키지에 위치, `disclosure → user.PortfolioRepository` 직접 의존, `extractTier()` private 박힘, Tier enum 이중 정의, FE 동일 로직 3~6회 중복
- **목표**: 도메인 경계 복구 + 네이밍 통일 + 중복 제거
- **BM 연관**: 없음 — 기능 변경 없음 (리팩토링)

---

## 요구사항

### BE 아키텍처

- [ ] **R1** `disclosure/services/DisclosureListItemResponse.java` 를 `disclosure/dto/` 패키지로 이동 — CLAUDE.md §3-2 도메인 모듈 표준 준수. import 경로 동시 갱신
- [ ] **R2** `AnalysisResponse.Tier` 중첩 enum 제거 — `UserEntity.Tier` 사용 또는 `shared/enums/Tier.java` 신규 + 전체 통합. `AnalysisQueryService` 의 Tier 변환 보일러플레이트 제거
- [ ] **R3** `DisclosureController.extractTier()` private 메서드를 `shared/security/SecurityUtils.extractTier(Authentication)` 또는 `@Component TierResolver` 로 추출 — 신규 컨트롤러(`AnalysisController` 등)도 동일 로직 재사용
- [ ] **R4** `disclosure → user(PortfolioRepository)` 직접 의존 제거 — `user/services/UserStockCodesProvider` 인터페이스 정의 후 disclosure 가 인터페이스만 의존. 또는 `shared/event` 기반 read-model 분리. CLAUDE.md §3-2 마스터 데이터 예외(stocks) 와 구분
- [ ] **R5** `DisclosureController` 의 Java 파라미터명 `stock_code` 를 `stockCode` 로 변경 — `@RequestParam("stock_code") String stockCode` 형태로 쿼리 매핑 유지 (Google Java Style)

### BE 타입 안전성

- [ ] **R6** `AnalysisResponse.similarDisclosures` 의 `List<Object>` 를 `List<SimilarDisclosureItem>` (record placeholder) 로 교체. Stage 3 구현 전이라도 타입 시그니처 확정

### FE 중복 제거

- [ ] **R7** `isPro` 체크 3파일 중복(`disclosures/[id]/page.tsx`, `portfolios/page.tsx`, `Sidebar.tsx`) → `lib/hooks/useTierCheck.ts` 신규. `const { isPro, isPremium, tier } = useTierCheck()`
- [ ] **R8** 활성 경로 판단 로직 2파일 중복(`Sidebar.tsx`, `BottomTabBar.tsx`) → `lib/utils/isActivePath.ts` 헬퍼 추출
- [ ] **R9** `portfolios/page.tsx` 의 `corp_name ?? stock_code` 6회 반복 → `<PortfolioListItem />` 컴포넌트 추출
- [ ] **R10** `window.location.href = "/login"` 3곳 분산(`client.ts`, `authStore.ts`, `auth.ts`) → `lib/constants.ts` 의 `LOGIN_PATH` 상수 + `authStore.logout()` 단일 경로로 집중
- [ ] **R11** `portfolios/new/page.tsx` API 에러 코드(`BUSINESS_RULE_VIOLATION`, `DUPLICATE_RESOURCE`) → `lib/api/errorCodes.ts` 의 `API_ERROR_CODES` const 객체
- [ ] **R12** `signup/profile/page.tsx:32` `isSubmitting` dead state 제거 — API 호출이 스킵된 상태에서 불필요한 상태 관리
- [ ] **R13** `disclosures/[id]/page.tsx` `expected_reaction === "UP"/"DOWN"` 매직 문자열 → `lib/api/disclosures.ts` 의 `EXPECTED_REACTION_CONFIG` 맵(label, colorClass 포함)
- [ ] **R14** `support@dartcommons.kr` 하드코딩 (disclosures/[id], settings) → `lib/constants.ts` 의 `SUPPORT_EMAIL` 상수
- [ ] **R15** `portfolios/page.tsx` 추천 종목 인라인 배열 → 모듈 스코프 `RECOMMENDED_STOCKS` 상수

### Lint · 컨벤션

- [ ] **R16** `DisclosureQueryService` 의 `.map(p -> p.getStockCode())` → `.map(PortfolioEntity::getStockCode)` (Google Java Style 메서드 참조)
- [ ] **R17** `DisclosureListItemResponse` 의 `ar != null ? ar.getX() : null` 4회 반복 → `Optional.ofNullable(ar).map(AnalysisResult::getX).orElse(null)` 패턴

### 테스트 보강

- [ ] **R18** 신규 `DisclosureController` · `AnalysisController` · `FeedbackService` 통합 테스트 추가 — Testcontainers + MockMvc. 티어별 필드 차등, 404 케이스, upsert 재투표, 페이지네이션 검증

---

## 영향 범위

- **영향 레이어**: backend (`disclosure`, `analysis`, `user`, `shared/security`) + frontend (`lib/hooks`, `lib/utils`, `lib/constants`, 다수 페이지)
- **DB 변경**: 없음
- **외부 계약**: 없음 — 시그니처 동일, 내부 리팩토링만

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../disclosure/dto/DisclosureListItemResponse.java` | R1 패키지 이동 |
| `backend/.../shared/enums/Tier.java` (신규) 또는 `UserEntity.Tier` 통합 | R2 |
| `backend/.../shared/security/SecurityUtils.java` (신규) | R3 extractTier 공통화 |
| `backend/.../user/services/UserStockCodesProvider.java` (신규) | R4 인터페이스 |
| `backend/.../disclosure/services/DisclosureQueryService.java` | R4 인터페이스 의존 + R16 메서드 참조 |
| `backend/.../disclosure/controllers/DisclosureController.java` | R3·R5 |
| `backend/.../analysis/dto/AnalysisResponse.java` | R2·R6 |
| `backend/.../disclosure/dto/DisclosureListItemResponse.java` | R17 Optional 패턴 |
| `backend/src/test/.../disclosure/DisclosureControllerTest.java` (신규) | R18 |
| `backend/src/test/.../analysis/AnalysisControllerTest.java` (신규) | R18 |
| `backend/src/test/.../analysis/FeedbackServiceIntegrationTest.java` (신규) | R18 |
| `frontend/src/lib/hooks/useTierCheck.ts` (신규) | R7 |
| `frontend/src/lib/utils/isActivePath.ts` (신규) | R8 |
| `frontend/src/components/domain/PortfolioListItem.tsx` (신규) | R9 |
| `frontend/src/lib/constants.ts` | R10·R14 |
| `frontend/src/lib/api/errorCodes.ts` (신규) | R11 |
| `frontend/src/lib/api/disclosures.ts` | R13 EXPECTED_REACTION_CONFIG |
| `frontend/src/app/(auth)/signup/profile/page.tsx` | R12 |
| `frontend/src/app/(app)/portfolios/page.tsx` | R9·R15 |

---

## 관련 패턴 / 과거 사례

- `sentiment-to-shared` (Done) — analysis 중첩 enum → shared 이관 선례. Tier 도 동일 패턴 적용
- `notification-dispatcher` (Done) — 도메인 간 이벤트 기반 의존 분리 패턴
- CLAUDE.md §3-2 — 도메인 모듈 표준, import 방향(shared → 도메인), 마스터 데이터 예외 명시
- CLAUDE.md §6 — Google Java Style, TS 컨벤션

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| 패키지 이동 / 인터페이스 추출로 광범위 import 변경 | 단일 PR 로 일괄 처리. IDE refactor 자동화. 컴파일 + 79개 테스트 전수 통과 게이트 |
| Tier enum 통합 시 직렬화 호환성 | enum name 동일 유지(FREE/PRO/PREMIUM) — 와이어 포맷 변경 없음 |
| FE useTierCheck 추출로 SSR/CSR 차이 발생 | hook 은 클라이언트 전용 — 'use client' 컴포넌트에서만 사용. SSR 페이지는 직접 prop drilling |
| 리팩토링 PR 비대화 → 리뷰 부담 | Wave 분할: BE Wave 1(R1~R6), FE Wave 2(R7~R15), Lint Wave 3(R16~R17), Test Wave 4(R18) |

---

## 권장 구현 방향

- Wave 1 (BE 패키지·인터페이스): R1·R3·R4·R5 동시 → 컴파일 검증
- Wave 2 (BE 타입 통합): R2·R6
- Wave 3 (FE 추출): R7~R15 — 페이지별 분리 가능
- Wave 4 (테스트 보강): R18 — 다른 Wave 의 회귀 게이트로 기능
- [[be-api-blocking-bugs-fix]] 와 충돌 가능 — 본 Spec 은 그 이후 머지

## Tech Review (dc-tech-review · 2026-06-10)

### 코드 vs Spec 대조 결과

| 요구사항 | 현재 코드 상태 | 작업 필요 |
|----------|---------------|----------|
| R1 DTO → dto/ | `DisclosureListItemResponse` 가 `services/` 패키지에 위치 — 확인됨 | ✅ 이동 |
| R2 AnalysisResponse.Tier | `AnalysisResponse.java:64` 중첩 enum 존재, `AnalysisQueryService:32-35` 변환 보일러플레이트 확인 | ✅ shared/enums/Tier 신규 |
| R3 extractTier | `DisclosureController.extractTier()` private (line 94), AnalysisController는 별도 미존재(상세 R3에서는 DisclosureController만 사용) | ✅ SecurityUtils 추출 |
| R4 disclosure → user 의존 | `DisclosureQueryService:45` PortfolioRepository 직접 의존 + `FeedbackService:43` 동일 | ✅ UserStockCodesProvider 인터페이스 + has 메서드 |
| R5 stock_code → stockCode | `DisclosureController:52` `@RequestParam stock_code` 확인됨 | ✅ Java 파라미터명 변경 |
| R6 List\<Object\> | `AnalysisResponse:46` `List<Object> similarDisclosures` 확인됨 | ✅ record placeholder |
| R7 isPro 중복 | portfolios·disclosures·disclosures/[id]·Sidebar 4파일 확인 (Spec은 3파일) | ✅ useTierCheck 훅 |
| R10 LOGIN_PATH | **이미 완료** — fe-auth-token-refresh-flow-rewrite에서 lib/constants.ts + 3개 파일 모두 LOGIN_PATH 상수 사용 중 | ⬛ skip |
| R8 isActivePath | Sidebar·BottomTabBar 미확인 (확인 필요) | ⚠️ 구현 단계 확인 |
| R9·R11~R17 | 미확인 — 구현 단계에서 grep 재확인 | ⚠️ |
| R18 신규 컨트롤러 테스트 | `DisclosureControllerTest` 이미 존재(security-hardening Wave에서 10케이스 추가). AnalysisController·FeedbackService 통합 테스트 신규 필요 | ✅ 2건 신규 |

### 아키텍처 분해

- **영향 레이어**: backend(`disclosure`, `analysis`, `user`, `shared/enums`, `shared/security`) + frontend(`lib/hooks`, `lib/utils`, `lib/api`, `components/domain`, 다수 페이지)
- **신규 BE**: `shared/enums/Tier.java`, `shared/security/SecurityUtils.java`, `user/services/UserStockCodesProvider.java`, `analysis/dto/SimilarDisclosureItem.java`(record), `AnalysisControllerTest.java`, `FeedbackServiceIntegrationTest.java`
- **신규 FE**: `lib/hooks/useTierCheck.ts`, `lib/utils/isActivePath.ts`, `lib/api/errorCodes.ts`, `components/domain/PortfolioListItem.tsx`
- **수정**: AnalysisResponse, AnalysisQueryService, DisclosureController, DisclosureQueryService, FeedbackService, DisclosureListItemResponse, lib/constants.ts, disclosures.ts, portfolios/page.tsx, signup/profile/page.tsx 외 다수

### 작업 카드

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `shared/enums/Tier.java` 신규 + `UserEntity.Tier` 통합 (와이어 포맷 동일 — name 유지) | BE/shared | 중 | - |
| 2 | `AnalysisResponse.Tier` 중첩 제거 + `AnalysisQueryService` switch 보일러플레이트 제거 | BE/analysis | 하 | #1 |
| 3 | `DisclosureListItemResponse` services/ → dto/ 패키지 이동 + import 갱신 | BE/disclosure | 하 | - |
| 4 | `shared/security/SecurityUtils.extractTier()` 추출 + DisclosureController 사용 | BE/shared | 하 | #1 |
| 5 | `UserStockCodesProvider` 인터페이스 + impl(PortfolioRepository 위임). DisclosureQueryService·FeedbackService 의존 교체 | BE/user·disclosure·analysis | 중 | - |
| 6 | DisclosureController `@RequestParam("stock_code") String stockCode` 매핑 + DisclosureListItemResponse Optional 패턴(R17) + 메서드 참조(R16) | BE/disclosure | 하 | #3 |
| 7 | `SimilarDisclosureItem` record placeholder + `AnalysisResponse.similarDisclosures` 타입 교체 | BE/analysis | 하 | - |
| 8 | `lib/hooks/useTierCheck.ts` 신규 + 4파일 적용(portfolios·disclosures·disclosures/[id]·Sidebar) | FE/hooks | 하 | - |
| 9 | `lib/utils/isActivePath.ts` 헬퍼 + Sidebar·BottomTabBar 적용 | FE/utils | 하 | - |
| 10 | `components/domain/PortfolioListItem.tsx` 추출 + portfolios/page.tsx 적용 | FE/component | 하 | - |
| 11 | `SUPPORT_EMAIL` 상수(R14) + `RECOMMENDED_STOCKS` 모듈 스코프(R15) + `lib/api/errorCodes.ts`(R11) | FE/lib | 하 | - |
| 12 | `signup/profile` isSubmitting dead state 제거(R12) + `EXPECTED_REACTION_CONFIG` 맵(R13) | FE/lib·page | 하 | - |
| 13 | `AnalysisControllerTest` + `FeedbackServiceIntegrationTest` 신규 (Testcontainers + MockMvc) | BE/test | 중 | #1~#7 |

### DB / 마이그레이션 영향

- **마이그레이션 불필요**: 순수 코드 리팩토링. enum name 유지(FREE/PRO/PREMIUM)로 DB 컬럼·값 변경 없음
- **JPA mapping 영향**: `UserEntity.Tier`를 `shared/enums/Tier`로 옮길 경우 `@Enumerated(EnumType.STRING)` + import만 갱신. ddl-auto: validate 통과 확인 필요

### 외부 계약 영향

- **REST API**: 변경 없음 (`stock_code` 쿼리 파라미터는 `@RequestParam` 매핑으로 유지)
- **JSON 직렬화**: `Tier` enum의 JSON 값(FREE/PRO/PREMIUM) 동일 유지 — FE 영향 없음
- **DART/KRX/카카오**: 무관

### 리스크 & 법적 검토

| 리스크 | 대응 |
|--------|------|
| **R1 일괄 이동 시 광범위 import 변경** — 미커밋 변경과 충돌 | 워킹 트리 clean 상태에서 진행(현재 만족), wave 단위 분할 |
| **R4 인터페이스 분리로 user 도메인 변경 충돌** | UserStockCodesProvider impl을 user/services/에 두고 의존성 역전 — disclosure는 shared 인터페이스만 의존 |
| **Tier enum 통합 직렬화 호환성** | `name()` 동일 유지(FREE/PRO/PREMIUM). DB 컬럼 + JWT authority 문자열 + JSON 응답 모두 무영향 |
| **R7 useTierCheck SSR 차이** | `useAuthStore` 의존이므로 자동으로 `"use client"` 컴포넌트에서만 동작. SSR 페이지(server component)는 별도 처리 — 현재 (app)/portfolios/[id] 등은 모두 client component |
| **법적 리스크**: 기능 변경 없음 (자본시장법·개인정보·LLM 환각 무관) | - |

### 예상 wave 수

- **Wave 1** (BE 도메인 기반): #1 #3 #4 #6 — Tier shared 이관 + DTO 패키지 + SecurityUtils + DisclosureController 정리
- **Wave 2** (BE 의존 분리): #2 #5 #7 — AnalysisResponse Tier 적용 + UserStockCodesProvider + SimilarDisclosureItem
- **Wave 3** (FE 추출): #8 #9 #10 #11 #12 — 훅·유틸·컴포넌트·상수 일괄
- **Wave 4** (테스트 보강): #13 — AnalysisControllerTest + FeedbackServiceIntegrationTest

### 변경된 가정

- **R10은 제외**: `fe-auth-token-refresh-flow-rewrite`(2026-06-10 Done)에서 이미 `LOGIN_PATH` 상수 + 3개 파일 통합 완료. 본 Spec에서 작업 불필요
- **R3 범위 축소**: AnalysisController는 별도 존재하지 않음(DisclosureController가 `/analysis` 엔드포인트 처리). extractTier 사용처는 현재 DisclosureController 1곳뿐이지만, 향후 추가 컨트롤러 대비 SecurityUtils로 추출
- **R7 4파일**: Spec은 3파일이지만 실제 `disclosures/page.tsx`도 isPro 체크 보유 — 4파일 적용
