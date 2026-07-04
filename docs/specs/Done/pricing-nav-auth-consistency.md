---
type: spec
status: Done
created: 2026-06-22
updated: 2026-06-23
---

# 요금제 진입 시 로그인 사용자 네비/셸 정합성 Spec

> 상태: Draft → Approved (2026-06-22, dc-tech-review 승인) → **Done** (2026-06-23, 구현·리뷰·커밋 완료)
> 관련: [[design_structure]] §1·§5.1, [[api_spec]] §2.6, [[payment-pg-integration]]

## 배경 / 목적

로그인 상태의 사용자가 앱 셸(사이드바 있는 `(app)` 레이아웃) 안에서 사이드바 **"요금제"** 메뉴(`Sidebar.tsx:35`)를 클릭하면, `(public)/pricing` 마케팅 페이지로 이동한다. 이때:

1. **앱 셸(사이드바)을 잃고** `PublicNavbar`가 달린 퍼블릭 레이아웃으로 빠져나간다.
2. 우상단에 **"로그인" / "무료로 시작"** 버튼이 그대로 노출된다 — 이미 로그인한 사용자에게 명백히 어색한 CTA.

추가로, 이 어색함의 직접 원인인 **`PublicNavbar`의 주석-코드 불일치(실제 버그)**가 있다:
- `PublicNavbar.tsx:4` 주석: *"로그인 세션 감지 시 /dashboard 링크로 전환"*
- 실제 코드(`PublicNavbar.tsx:33~40`): 인증 상태와 **무관하게 항상** "로그인 / 무료로 시작"을 렌더 → 약속된 분기 미구현.

- **페르소나 연결**: Free → Pro 전환을 검토하는 기존 로그인 사용자(전환 퍼널 핵심). 어색한 비로그인 CTA는 업그레이드 동선의 신뢰를 떨어뜨린다.
- **BM 티어**: Free 사용자의 Pro/Premium 업셀 동선([[design_structure]] Zone F 인접).

## 요구사항

- [ ] 로그인 사용자가 `/pricing` 진입 시 "로그인 / 무료로 시작" CTA가 노출되지 않는다.
- [ ] `PublicNavbar`가 `authStore` 세션을 감지해, 로그인 시 주석대로 "대시보드로"(또는 동등한 인증 사용자 액션)로 전환된다 — 주석-코드 불일치 해소.
- [ ] 비로그인 사용자의 `/pricing` 경험(랜딩 → 요금제 → 무료로 시작/가입)은 회귀 없이 유지된다.
- [ ] 요금제 화면은 [[design_structure]] §1·§5.1의 **단일 D4/07 화면(Zone A)** 모델과 정합 유지(중복 화면 신설 지양).
- [ ] (선택/후속) 사이드바 "요금제" 위치 IA 점검 — 현재 `설정` 섹션 vs 명세 §5.1의 `계정 메뉴(드로어)` 동선.

## 영향 범위 (조사 결과)

- 영향 레이어: **frontend only** (BE/DB/외부계약 변경 없음)
- 영향 파일:
  - `frontend/src/components/layout/PublicNavbar.tsx` — **핵심 수정 대상**. 현재 인증 상태 무시하고 항상 로그인/가입 CTA 렌더(`33~40`). `authStore.user` 구독해 분기 추가 필요. (현재 `"use client"`이므로 `useAuthStore` 직접 사용 가능)
  - `frontend/src/components/layout/Sidebar.tsx:35` — 요금제 → `/pricing` 링크(웹 사이드바, `설정` 섹션). 동선 출발점.
  - `frontend/src/components/layout/HamburgerDrawer.tsx:20` — 요금제 → `/pricing`(모바일 드로어). 동일 동선, 함께 검증 필요.
  - `frontend/src/app/(public)/pricing/page.tsx`, `PricingClient.tsx` — 페이지 본문. **콘텐츠는 이미 티어 인지**(`PricingClient.tsx:45~57`: `user.tier`로 `isCurrent`/CTA 분기, 비로그인 시 `/signup`). 즉 **본문은 손댈 필요가 적고, 어긋난 곳은 셸/네비바뿐**.
- 비영향(확인됨):
  - `frontend/src/middleware.ts:14~24` — `/pricing`은 `PUBLIC_PATHS`에 포함, 로그인/비로그인 모두 접근 가능(정상). 변경 불필요.
  - `frontend/src/app/(app)/checkout/page.tsx` — `/checkout`은 `(app)` 그룹(앱 셸 유지). 요금제 CTA의 다음 단계로 정상 동작. 변경 불필요.
- DB 변경: **없음**
- 외부 계약: **없음** (DART/KRX/카카오/LLM 무관)

## 관련 패턴 / 과거 사례

- `docs/solutions` 디렉터리 부재 — 과거 해결 사례 없음(Step 0 검색: 해당 없음).
- **인증 상태 구독 기존 패턴**: `Sidebar.tsx:40`(`useAuthStore`), `HamburgerDrawer.tsx:26`(`useAuthStore`), `PricingClient.tsx:45`(`useAuthStore`) — `PublicNavbar`도 동일 훅으로 `user` 구독하면 일관됨.
- **IA 근거(SSOT)**: [[design_structure]] §1.1은 요금제를 **Zone A(진입/마케팅)** 단일 화면 `D4/07`로 정의. §5.1 플로우맵은 동일 `D4/07`을 (a) 비로그인 랜딩 진입과 (b) **로그인 후 아바타→계정 메뉴(드로어)→요금제** 양쪽에서 진입하도록 모델링. → **요금제는 본래 양쪽이 공유하는 단일 화면**이라는 설계 의도가 명문화돼 있음.
- 결제 연동 후속은 [[payment-pg-integration]] Draft가 별도 관리 — 본 Spec은 결제 미연동 전제(현행 mockup) 유지.

## 리스크 / 법적 검토

- 자본시장법/개인정보: **해당 없음**(네비게이션/레이아웃 UX 변경, 분석·금융 데이터 비관여).
- 디자인 토큰(CLAUDE.md §6-4): 네비바 CTA 추가/전환 시 색·간격은 기존 `buttonVariants`/토큰만 사용(하드코딩 금지).
- 접근성(§6-5): 전환되는 링크/버튼에 `aria-label`·`:focus-visible`·키보드 경로 유지.
- 회귀 리스크: 비로그인 퍼널("무료로 시작" → `/signup`) 손상 금지 — 분기 조건이 인증 상태에만 의존하도록 보장.

## 권장 구현 방향

두 후보안. **A안 권장.**

### A안 (권장) — `/pricing` 단일 화면 유지 + `PublicNavbar` auth-aware화
- `PublicNavbar`가 `useAuthStore().user`를 구독:
  - **비로그인**: 현행 유지("로그인" / "무료로 시작").
  - **로그인**: 주석대로 "대시보드로"(→ `/dashboard`) 등 인증 사용자용 액션으로 전환. "로그인/무료로 시작" 제거.
- 근거:
  1. **주석-코드 불일치(버그) 자체를 해소** — 어느 안을 택하든 이 버그는 독립적으로 고쳐야 함(로그인 사용자가 랜딩/직접 URL로 `/pricing` 접근하는 모든 경로에 영향).
  2. [[design_structure]]의 **단일 D4/07(Zone A) 설계 의도와 정합** — 화면 중복 신설 없음.
  3. **최소 변경**(사실상 1파일) + 기존 `useAuthStore` 패턴 재사용.
- 트레이드오프: 로그인 사용자가 요금제 열람 동안 사이드바를 잠시 벗어남. 단, 명세상 요금제는 마케팅 Zone A 화면이므로 "앱 허브를 벗어나 요금제/마케팅을 본다"는 동선은 의도된 모델에 부합.

### B안 (대안) — `(app)` 그룹 내 요금제 뷰 신설(사이드바 유지)
- `(app)/pricing`(또는 `/upgrade`) 라우트를 신설해 사이드바 유지된 채 `PricingClient`/`PlanCard` 재사용.
- 장점: 로그인 사용자의 앱 셸 연속성 최상.
- 단점:
  - **D4/07 화면 사실상 2벌 유지**(퍼블릭/앱) — [[design_structure]] 단일화면 원칙과 divergence, 레이아웃 분기 비용.
  - **`PublicNavbar` 버그는 여전히 잔존**(비로그인/직접 URL 경로) → A안의 네비바 수정을 **추가로** 해야 함. 즉 B안 = A안 + 신규 화면.
- 채택 시: 명세(§1.1·§5.1) 동시 갱신 + 화면 번호 부여 필요.

> **결론**: `PublicNavbar` auth-aware 수정은 안과 무관하게 필수인 버그 픽이며, 그것만으로 사용자가 보고한 어색함이 해소된다. 명세 정합·최소 변경 관점에서 **A안**을 권장하고, 앱 셸 연속성이 제품적으로 강하게 요구될 경우에만 B안으로 확장한다. 최종 선택은 `/dc-tech-review` + 사용자 승인에서 확정.

### 확인 필요
- A안에서 로그인 사용자용 네비 액션을 "대시보드로" 단일 링크로 할지, 아바타/계정 메뉴까지 노출할지 — 디자인 디테일(tech-review에서 확정).
- 사이드바 "요금제"의 IA 위치(현 `설정` 섹션 vs 명세 §5.1 `계정 드로어`) 조정 여부 — 본 Spec 범위에 포함할지 결정 필요(후속 분리 권장).

## Tech Review (dc-tech-review · 2026-06-22)

### 핵심 발견 — 인증 상태 hydration 갭 (A안 구현 방식 보정)

Spec의 A안은 *"PublicNavbar가 `useAuthStore().user`를 구독"* 으로 가정했으나, 코드 검증 결과 **store 단독 구독으로는 일부 진입 경로가 누락**된다:

- `dr_session` 쿠키는 **`httpOnly: true`**(`frontend/src/app/api/auth/session/route.ts:23`, `callback/[provider]/route.ts:63`) → **클라이언트 JS로 읽을 수 없음**.
- `fetchMe()`는 **`(app)` 레이아웃 내부**(`AuthBroadcastListener.tsx:25`)와 일부 auth 플로우에서만 호출 — **`(public)` 레이아웃은 `authStore.user`를 hydrate하지 않음**.

| 진입 경로 | store 단독(`useAuthStore().user`) | 서버 쿠키 prop |
|---|---|---|
| 사이드바/드로어 `<Link>` → `/pricing` (보고된 주 동선, 클라이언트 nav) | ✅ user 유지(메모리) | ✅ |
| `/pricing` 하드 리프레시 / 직접 URL (로그인 상태) | ❌ user=null → 로그인·가입 재노출 | ✅ |
| 비로그인 진입 | ✅ | ✅ |

→ **결론: `(public)/layout.tsx`(이미 서버 컴포넌트)에서 `cookies().get("dr_session")` presence를 읽어 `PublicNavbar`에 `isAuthenticated` prop으로 주입**한다. 이는 `middleware.ts:28`과 **동일한 presence-only 판정**(유효성은 API가 검증)이라 httpOnly 위반·중복 로직 없음. `LandingRedirect` 류 클라이언트 `fetchMe` 재도입(미들웨어 주석이 경고한 401+refresh 콘솔 에러)도 회피. 닉네임/티어 등 풍부한 표시는 클라이언트 nav 시 store로 점진 강화 가능(선택).

### 아키텍처 분해
- 영향 레이어: **frontend only** (`components/layout`, `app/(public)`). BE/DB/외부계약 무관.
- 수정 대상: `PublicNavbar.tsx`(분기 + 주석 정정), `(public)/layout.tsx`(쿠키 presence → prop).
- 신규: 없음(컴포넌트/라우트 신설 없음 — A안).
- 비변경 확정: `Sidebar.tsx`·`HamburgerDrawer.tsx`(링크 그대로), `middleware.ts`, `(app)/checkout`, `PricingClient.tsx`(이미 티어 인지).

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `(public)/layout.tsx`에서 `cookies().get("dr_session")` presence 판정 → `<PublicNavbar isAuthenticated={…} />` prop 주입 | frontend/(public) | FE | 하 | - |
| 2 | `PublicNavbar` `isAuthenticated` 분기 렌더 — 로그인: "대시보드로"(→`/dashboard`) 단일 CTA / 비로그인: 현행("로그인"·"무료로 시작") 유지 + 주석(4행) 코드와 일치하게 정정 | frontend/layout | FE | 하 | #1 |
| 3 | 접근성·토큰 검증 — 전환 CTA에 `aria-label`·`:focus-visible`, 색·간격은 `buttonVariants`/토큰만(하드코딩 금지, §6-4/§6-5) | frontend/layout | FE | 하 | #2 |
| 4 | 회귀·시각 검증 — (a)비로그인 `/pricing` 퍼널(`무료로 시작`→`/signup`) (b)로그인 사이드바 클라 nav (c)로그인 `/pricing` 하드리프레시 3경로 (`/dc-review-frontend` Playwright) | frontend | FE | 하 | #2,#3 |

### DB / 마이그레이션 영향
- **없음.** Flyway 마이그레이션 불필요. 스키마(`db_schema.md`) 무변경.

### 외부 계약 영향
- **없음.** DART/KRX/카카오 알림톡/LLM 프롬프트 무관.

### 리스크 & 법적 검토
- **자본시장법/개인정보**: 해당 없음(네비 UX 변경, 분석·금융 데이터 비관여).
- **회귀 리스크(주)**: 비로그인 가입 퍼널 손상 금지 — `isAuthenticated=false` 분기가 현행과 픽셀·동선 동일해야 함(카드 #4가 게이트).
- **보안**: `dr_session`은 presence만 판정(값·만료 검증은 BE). 토큰을 클라이언트로 노출하지 않음 — httpOnly 유지.
- **디자인 토큰/접근성**: §6-4(토큰 강제)·§6-5(aria/focus) 준수 — 카드 #3가 게이트.

### 범위 밖(후속 분리 권장)
- 사이드바 "요금제" IA 위치(현 `설정` 섹션 vs 명세 §5.1 `계정 드로어`) 조정 — 별도 Spec 권장. 본 Spec은 **셸/네비바 정합**에 한정.
- B안(`(app)` 그룹 내 요금제 뷰 신설)은 화면 중복·명세 divergence·PublicNavbar 버그 잔존으로 **비권장**. 앱 셸 연속성이 제품 요구로 확정될 때만 별도 Spec으로.

### 예상 wave 수
- **1 wave** (단일 소규모 FE PR, 2파일 수정 + 검증).

### 결정 확정(2026-06-22 · 사용자 승인)
1. ✅ **A안 확정** — `/pricing` 단일 화면 유지 + `PublicNavbar` auth-aware화(서버 쿠키 prop). B안 비채택.
2. ✅ 로그인 사용자 네비 CTA = **"대시보드로" 단일 링크**(→`/dashboard`). 아바타/계정 메뉴 미노출.

> 결정 확정 → `/dc-spec-move pricing-nav-auth-consistency Approved` → `/dc-implement`.
