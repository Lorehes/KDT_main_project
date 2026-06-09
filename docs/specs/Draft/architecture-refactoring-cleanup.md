---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# 아키텍처 · 유지보수성 정리 Spec

> 상태: **Draft** (dc-plan 생성, 2회 코드 리뷰 종합)

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

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
