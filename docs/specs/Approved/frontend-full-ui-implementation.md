---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-09
reviewed: 2026-06-09
---

# 프론트엔드 전체 UI 구현 Spec

> 상태: **Approved** (2026-06-09, dc-tech-review 승인)

## 배경 / 목적

`docs/design/`에 업로드된 웹/모바일 화면 명세서·플로우맵(HTML 4종)을 기반으로, 현재 빈 스텁 상태인 Next.js 15 App Router 프론트엔드를 실제 UI가 있는 완성 화면으로 구현한다.

- **현황**: 라우트 파일 10개 전부 "준비 중" 텍스트. `layout.tsx`, `providers.tsx`, `components/ui/button.tsx`, `lib/utils.ts`만 실제 코드. 디자인 토큰(`globals.css` sentiment 변수)은 정의 완료.
- **목표**: 7개 Zone(A진입·B온보딩·C종목·D공시·E알림·F결제·G계정) 웹 26화면 + 모바일 26화면을 반응형으로 구현. 백엔드 API 연동 준비 상태(타입·훅 선언)까지 포함.
- **페르소나**: A(직장인 입문) ~ F(소모임 운영자) 전 구간 — 한국 증시 관행(호재=빨강/악재=파랑) 준수, 시니어 큰 글자 배려, 면책 문구 상시 노출.
- **BM**: Free(종목 3개·일 5건) / Pro(₩9,900/월) / Premium(₩29,900/월) 티어 차등 UX 포함.

---

## 요구사항

### R1 · 레이아웃 셸 (공통 기반)

- [ ] **웹 앱 셸**: 상단 TopBar(64px) + 좌측 Sidebar(240px) + 메인 영역 — 인증 필요 전 화면 공통
- [ ] **모바일 앱 셸**: 상단 AppBar(56px, Navy) + 하단 BottomTabBar(대시보드·종목·알림·요금제) — 모바일 공통
- [ ] **햄버거 드로어**: 모바일 우측 슬라이드 드로어 (전체 메뉴 허브)
- [ ] **온보딩 레이아웃**: 좌측 네이비 피치 패널 + 우측 폼의 스플릿 (웹 1440px), 모바일 단일 컬럼
- [ ] **퍼블릭 레이아웃**: 랜딩·요금제 전용 TopNav (로그인/무료 시작 CTA)

### R2 · Zone A — 진입 / 마케팅

- [ ] **D1/m01 랜딩**: 히어로(가치 카피+CTA) + 기능 4종 그리드 + 면책 고지. 로그인 세션 시 `/dashboard` 리다이렉트
- [ ] **D4/m07 요금제**: Free/Pro(인기 배지)/Premium 3열 비교. 현재 등급 바인딩. CTA → 로그인 또는 결제

### R3 · Zone B — 가입 / 온보딩 (4단계 스테퍼)

- [ ] **D5/m02 로그인·가입**: 카카오·구글 소셜 + 이메일 분기. 스플릿 레이아웃. 동의 고지 상시
- [ ] **D6/m10 이메일 인증 STEP 1/4**: 6칸 OTP(자동 포커스·붙여넣기·타이머·재전송). 불일치/만료 인라인 에러
- [ ] **D7/m11 약관 동의 STEP 2/4**: 전체 동의 토글 + 필수 4 / 선택 1. 자본시장법 제6조·제17조 고지. 동의 시각/버전 로깅
- [ ] **D8/m12 휴대폰 인증 STEP 3/4**: 번호 입력 → SMS 인증. 카카오 채널 추가 안내. "나중에" 스킵 허용
- [ ] **D9/m13 프로필 입력 STEP 4/4**: 투자 경험(라디오) + 주 사용 시점(세그먼트). 모두 선택 사항
- [ ] **D10/m14 가입 완료**: 환영 + 시작 체크리스트(종목·알림). 온보딩 완료 이벤트 로깅
- [ ] **D11/m15 빈 대시보드**: 종목 0건 Empty state. "＋ 종목 등록" + "이런 종목은 어때요?" 추천 카드

### R4 · Zone C — 종목 관리

- [ ] **D12/m05 종목 등록**: 등록 리스트 + 검색창. Free 3개 제한 표시(쿼터 바)
- [ ] **D12b/m05b 종목 검색**: 실시간 자동완성 드롭다운 (`GET /api/v1/stocks/search`)
- [ ] **D13/m22 종목 등록 상세**: 매수 평균가·수량 입력(AES-256 암호화 전제). 알림 on/off 토글. 저장 → 대시보드

### R5 · Zone D — 핵심 · 대시보드 / 공시

- [ ] **D2/m03 대시보드**: 통계 카드 4종(오늘 공시수·손익·등) + DisclosureCard 피드 테이블. Progressive Disclosure(펼치기/접기)
- [ ] **D15/m23 공시 피드**: 호재/악재/보류 필터 칩 + 날짜 그룹. 3개월 이력 Pro 잠금(TierGate)
- [ ] **D3/m04 공시 상세 Free+Pro**: 판정 배지·근거·3줄 요약 + 유사사례 주가 반응(Pro). 신뢰도 바. DART 원문 링크
- [ ] **D17/m17 공시 상세 Premium**: 재무 영향 테이블 + 업황·경쟁사 비교. Premium TierGate
- [ ] **D18/m20 분석 피드백**: "도움됨/부정확" 선택 + 오류 유형 칩 + 의견. `POST /api/v1/analyses/{id}/feedback`

### R6 · Zone E — 알림

- [ ] **D14/m06 알림 설정**: 채널 토글(알림톡·이메일·푸시) + 유형 토글(호재·악재·보류) + 야간 보류 시간대
- [ ] **D25/m08 알림톡 미리보기**: 카카오 채팅창 형식. 템플릿 구성·면책 고지·발송 정책(플랜별 한도) 표시
- [ ] **D23/m25 알림 모달/시트**: 벨 클릭 팝오버(웹) / 상단 시트(모바일). 최근 4건 + 안읽음 점 + "모두 읽음"
- [ ] **D24/m26 알림 센터**: 날짜 그룹(오늘·어제·주) + 필터(안읽음·유형). 행 클릭 → 읽음 처리 + 공시 상세 이동

### R7 · Zone F — 결제 / 구독

- [ ] **D21/m18 Pro 업셀 모달**: 잠금 기능 클릭 시 업그레이드 유도. 잠긴 기능 미리보기 + Pro 혜택 요약
- [ ] **D19/m19 결제 (카드 등록됨)**: 7일 무료체험 Checkout. 결제 요약 + 등록 카드 선택 + 해지 안내
- [ ] **D20/m19b 결제 (신규 카드)**: 카드 번호·유효기간 입력 + 카카오페이 연결
- [ ] **D20b/m19c 카카오페이**: 연결 계정 표시 + 알림톡 결제 인증. 완료 → 대시보드

### R8 · Zone G — 계정 / 기타

- [ ] **D26/m09 계정 메뉴 드롭다운/드로어**: 프로필·플랜 요약 + 계정 바로가기 + 로그아웃
- [ ] **D16/m24 마이페이지·설정**: 프로필·현재 플랜·다음 결제일·계정/보안 설정 행
- [ ] **D22/m21 공유 카드**: 포트폴리오 주간 요약 이미지 카드. 이미지 다운로드/공유 버튼

### R9 · 공통 컴포넌트 (도메인)

- [ ] `SentimentBadge` — 호재/악재/중립/판단보류, 색+아이콘+텍스트 3중 표기(색맹 대응)
- [ ] `ConfidenceMeter` — 신뢰도 0~1 Progress 바 + "판단 보류" 임계 처리
- [ ] `DisclaimerNotice` — 분석/알림 하단 면책 문구 + 신고 경로
- [ ] `TierGate` — 티어 미달 컨텐츠 잠금 오버레이 + 업셀 CTA
- [ ] `DisclosureCard` — 공시 피드 카드 (Progressive Disclosure 펼치기/접기)
- [ ] `PriceReactionChart` — Recharts LineChart, 색+직접 라벨 (Pro)
- [ ] `FeedbackPrompt` — 분석 정확도 평가 폼
- [ ] `StockSearchCombobox` — 종목 검색 자동완성
- [ ] `OTPInput` — 6칸 OTP 입력 (자동 포커스·붙여넣기 지원)
- [ ] `PlanCard` — 요금제 카드 (현재 등급 바인딩)
- [ ] `ProUpsellModal` — 업셀 모달

### R10 · 접근성 / 법적 요건

- [ ] 모든 인터랙티브 요소 `aria-label` + `:focus-visible` + Tab/Esc/Enter 키보드 경로
- [ ] 본문 대비 4.5:1 이상 (기존 sentiment 토큰 사용 — 이미 globals.css에 정의)
- [ ] `prefers-reduced-motion` 존중
- [ ] 랜딩·가입 화면에 "투자 자문 아님" 면책 고지 상시 노출
- [ ] 공시 상세·알림 모든 화면에 `DisclaimerNotice` + `FeedbackPrompt`
- [ ] 매수가·수량 입력 필드: 평문 로깅 금지 처리(콘솔 출력·sentry 마스킹)

---

## 영향 범위 (조사 결과)

- **영향 레이어**: frontend 전용 (백엔드 변경 없음. API는 선언 타입/훅만 추가)
- **DB 변경**: 없음
- **외부 계약**: 없음 (카카오페이·알림톡은 MVP에서 UI mockup까지만)

### 신규 생성 파일

```
frontend/src/
├── app/
│   ├── (public)/                       # Public 레이아웃 그룹
│   │   ├── layout.tsx                  # 퍼블릭 NavBar
│   │   └── pricing/page.tsx            # ← 기존 이동
│   ├── (auth)/                         # 온보딩 레이아웃 그룹
│   │   ├── layout.tsx                  # 스플릿 레이아웃
│   │   ├── login/page.tsx              # ← 기존 이동
│   │   ├── signup/
│   │   │   ├── page.tsx                # 가입 진입 (= D5/m02)
│   │   │   ├── verify/page.tsx         # D6/m10 이메일 인증
│   │   │   ├── terms/page.tsx          # D7/m11 약관 동의
│   │   │   ├── phone/page.tsx          # D8/m12 휴대폰 인증
│   │   │   ├── profile/page.tsx        # D9/m13 프로필 입력
│   │   │   └── complete/page.tsx       # D10/m14 가입 완료
│   ├── (app)/                          # 인증 필요 레이아웃 그룹
│   │   ├── layout.tsx                  # AppShell (Sidebar+TopBar / BottomTabBar)
│   │   ├── dashboard/page.tsx          # ← 기존 교체 (D2/m03)
│   │   ├── disclosures/
│   │   │   ├── page.tsx                # D15/m23 공시 피드
│   │   │   └── [id]/page.tsx           # ← 기존 교체 (D3/m04/D17/m17)
│   │   ├── portfolios/page.tsx         # ← 기존 교체 (D12/m05)
│   │   ├── notifications/
│   │   │   ├── page.tsx                # ← 기존 교체 (D24/m26)
│   │   │   └── settings/page.tsx       # D14/m06
│   │   ├── settings/page.tsx           # D16/m24 마이페이지
│   │   └── checkout/
│   │       ├── page.tsx                # D19/m19
│   │       └── new/page.tsx            # D20/m19b
│   └── page.tsx                        # ← 기존 교체 (D1/m01 랜딩)
│
├── components/
│   ├── layout/
│   │   ├── AppShell.tsx                # 웹 Sidebar+TopBar 셸
│   │   ├── MobileShell.tsx             # 모바일 AppBar+BottomTabBar 셸
│   │   ├── Sidebar.tsx                 # 좌측 글로벌 사이드바
│   │   ├── TopBar.tsx                  # 상단 바 (검색·벨·아바타)
│   │   ├── BottomTabBar.tsx            # 모바일 하단 탭바
│   │   ├── HamburgerDrawer.tsx         # 모바일 햄버거 드로어
│   │   ├── AuthLayout.tsx              # 온보딩 스플릿 레이아웃
│   │   └── PublicNavbar.tsx            # 퍼블릭 상단 네비
│   ├── domain/
│   │   ├── SentimentBadge.tsx          # 호재/악재/중립/판단보류 배지
│   │   ├── ConfidenceMeter.tsx         # 신뢰도 Progress
│   │   ├── DisclaimerNotice.tsx        # 면책 고지
│   │   ├── TierGate.tsx                # 티어 잠금 오버레이
│   │   ├── DisclosureCard.tsx          # 공시 피드 카드 (Progressive Disclosure)
│   │   ├── PriceReactionChart.tsx      # Recharts 주가 반응 차트
│   │   ├── FeedbackPrompt.tsx          # 분석 정확도 평가 폼
│   │   ├── StockSearchCombobox.tsx     # 종목 자동완성
│   │   ├── OTPInput.tsx                # 6칸 OTP 입력
│   │   ├── PlanCard.tsx                # 요금제 비교 카드
│   │   └── ProUpsellModal.tsx          # 업셀 모달
│   └── ui/                             # shadcn/base-ui 컴포넌트 (기존 + 추가)
│       ├── button.tsx                  # ← 기존
│       ├── input.tsx                   # 신규 추가
│       ├── badge.tsx
│       ├── card.tsx
│       ├── dialog.tsx                  # 모달용
│       ├── sheet.tsx                   # 모바일 시트용
│       ├── tabs.tsx
│       ├── switch.tsx
│       ├── progress.tsx
│       ├── popover.tsx
│       └── ... (필요 시 추가)
│
├── lib/
│   ├── utils.ts                        # ← 기존
│   ├── api/
│   │   ├── client.ts                   # fetch 래퍼 (Bearer JWT, 에러 파싱)
│   │   ├── auth.ts                     # auth API 타입 + 훅
│   │   ├── disclosures.ts              # 공시 API 타입 + 훅
│   │   ├── portfolios.ts               # 포트폴리오 API 타입 + 훅
│   │   ├── notifications.ts            # 알림 API 타입 + 훅
│   │   └── stocks.ts                   # 종목 검색 API 타입 + 훅
│   ├── stores/
│   │   ├── authStore.ts                # Zustand: 인증 세션 (access_token)
│   │   └── uiStore.ts                  # Zustand: UI 상태 (드로어 열림 등)
│   └── schemas/
│       ├── authSchemas.ts              # Zod: 가입·로그인 폼 검증
│       ├── portfolioSchemas.ts         # Zod: 종목 등록 폼
│       └── profileSchemas.ts           # Zod: 프로필 입력 폼
```

### 수정할 기존 파일

| 파일 | 변경 내용 |
|------|------|
| `frontend/src/app/layout.tsx` | Pretendard/IBM Plex Mono 폰트 추가, 기본 bg 색상 |
| `frontend/src/app/globals.css` | `--color-navy`(#0F2A47), `--color-blue`(#1E6FE0) 추가, 브랜드 primary 색상 반영 |
| `frontend/src/app/page.tsx` | 랜딩 페이지(D1)로 전면 교체 |
| `frontend/src/app/dashboard/page.tsx` | 대시보드(D2)로 교체 |
| `frontend/src/app/signup/page.tsx` | 가입 진입(D5)으로 교체 |
| `frontend/src/app/login/page.tsx` | 로그인 화면으로 교체 |
| `frontend/src/app/portfolios/page.tsx` | 종목 등록(D12)으로 교체 |
| `frontend/src/app/notifications/page.tsx` | 알림 센터(D24)로 교체 |
| `frontend/src/app/pricing/page.tsx` | 요금제(D4)로 교체 |
| `frontend/src/app/disclosures/[id]/page.tsx` | 공시 상세(D3/D17)로 교체 |
| `frontend/src/app/providers.tsx` | NextAuth.js v5 SessionProvider 추가 (확인 필요: 패키지 추가 필요) |

---

## 관련 패턴 / 과거 사례

- **design_structure.md**: SentimentBadge, ConfidenceMeter, DisclaimerNotice, TierGate, PriceReactionChart, FeedbackPrompt, StockSearchCombobox 컴포넌트 설계 명세 완료 — 그대로 구현
- **api_spec.md §2**: 라우트별 소비 API 매핑 완료 (design_structure.md §1.1)
- **button.tsx**: `@base-ui/react` 기반 — 신규 UI 컴포넌트도 `@base-ui/react` 또는 동등한 headless 라이브러리 사용 (shadcn 직접 import가 아닌 base-ui 패턴 유지)
- **globals.css**: 감성 토큰(sentiment-positive/negative/neutral/withheld) 이미 light/dark 양쪽 정의 완료 — 추가 없이 사용
- **providers.tsx**: TanStack Query `QueryClient` 이미 설정 완료 (staleTime 60s, retry 1) — NextAuth SessionProvider 추가만 필요

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| **투자 권유 표현 금지** (자본시장법 §11.1) | CTA 버튼·요약·근거 텍스트에 "매수/매도 추천", "꼭 사세요" 금지. 모든 분석 화면에 `DisclaimerNotice` 상시 노출 |
| **매수가·수량 평문 노출** | 입력 필드 `autoComplete="off"`, 콘솔 로그·Sentry 전송 시 마스킹 처리 |
| **판단 보류 표시** | `is_withheld=true` 또는 `confidence < 0.5`(임계치 확인 필요) 시 호재/악재 단정 UI 금지, SentimentBadge를 "판단 보류" 상태로 렌더 |
| **색맹 접근성** | SentimentBadge는 색+아이콘(▲▼―)+텍스트 3중 표기 필수 |
| **NextAuth.js v5** | `package.json`에 `next-auth` 미설치 — 구현 전 `pnpm add next-auth@beta` 필요(확인 필요) |
| **카카오페이 결제** | MVP 범위: UI mockup까지만. 실결제 연동은 별도 Spec |
| **모바일 반응형** | 웹 1440px + 모바일 390px 양쪽 커버. Tailwind `md:` 브레이크포인트 기준으로 레이아웃 전환 (`md:hidden` / `hidden md:flex`) |

---

## 권장 구현 방향

### 레이아웃 전략

**접근법 A (선택)**: Next.js App Router **Route Groups** 활용
- `(public)`: 랜딩·요금제 — PublicNavbar
- `(auth)`: 온보딩 — AuthLayout (스플릿)
- `(app)`: 인증 필요 — AppShell (반응형: 웹 Sidebar+TopBar / 모바일 AppBar+BottomTabBar)

**접근법 B**: 단일 layout + conditional rendering
- 구조가 단순하지만 레이아웃 전환 시 불필요한 hydration 발생 가능

→ A 채택. 각 그룹별 명확한 레이아웃 경계, 미들웨어(`middleware.ts`)로 `(app)` 보호.

### 반응형 전략

- `md:` (≥768px) = 웹 뷰 (Sidebar 표시, BottomTabBar 숨김)
- `< md` = 모바일 뷰 (Sidebar 숨김, BottomTabBar 표시, AppBar 표시)
- 공시 상세 2단 그리드 → 모바일 단일 컬럼

### API 연동 준비 수준

- MVP 단계: **타입 정의 + TanStack Query 훅 선언**까지. 실제 fetch는 백엔드 준비 상태에 맞춰 단계적 활성화
- 미연결 상태에서는 mock 데이터로 렌더 (타입 정합성 보장)

### 구현 파급 파동 (Wave 분리 권장)

| Wave | 범위 | 선행 조건 |
|------|------|------|
| **W1** | 레이아웃 셸 + 디자인 토큰 보완 + 공통 컴포넌트 (R1·R9) | 없음 |
| **W2** | Zone A 진입 (랜딩·요금제) | W1 완료 |
| **W3** | Zone B 온보딩 전체 (가입 4단계 + 완료) | W1 완료 |
| **W4** | Zone D 핵심 (대시보드·공시 피드·공시 상세) | W1·W3 완료 |
| **W5** | Zone C 종목 관리 | W1·W4 완료 |
| **W6** | Zone E 알림 (설정·센터·모달) | W1·W4 완료 |
| **W7** | Zone F 결제 + Zone G 계정·공유 | W1~W4 완료 |

---

## 화면 인벤토리 (설계 참조)

### Zone A · 진입

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D1/m01 | `/` | 랜딩 | PUBLIC | 없음 |
| D4/m07 | `/pricing` | 요금제 | PUBLIC | `GET /pricing/plans` |

### Zone B · 온보딩

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D5/m02 | `/login`, `/signup` | 로그인·가입 | PUBLIC | `POST /auth/signup`, `GET /auth/oauth/{p}/url` |
| D6/m10 | `/signup/verify` | 이메일 인증 | PUBLIC | `POST /auth/email/verify` (확인 필요) |
| D7/m11 | `/signup/terms` | 약관 동의 | PUBLIC | `POST /consents` |
| D8/m12 | `/signup/phone` | 휴대폰 인증 | USER | `POST /users/me/phone/verify` |
| D9/m13 | `/signup/profile` | 프로필 입력 | USER | `PATCH /users/me` |
| D10/m14 | `/signup/complete` | 가입 완료 | USER | 없음 |
| D11/m15 | `/dashboard` | 빈 대시보드 | USER | `GET /users/me` |

### Zone C · 종목 관리

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D12/m05 | `/portfolios` | 종목 등록 | USER | `GET /portfolios`, `GET /stocks/search` |
| D12b/m05b | `/portfolios` (검색 상태) | 종목 검색 | USER | `GET /stocks/search?q=` |
| D13/m22 | `/portfolios/new` | 종목 상세 등록 | USER | `POST /portfolios` |

### Zone D · 핵심·공시

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D2/m03 | `/dashboard` | 대시보드 | USER | `GET /disclosures?scope=portfolio`, `GET /users/me` |
| D15/m23 | `/disclosures` | 공시 피드 | USER | `GET /disclosures`, `GET /disclosures?filter=` |
| D3/m04/D17/m17 | `/disclosures/[id]` | 공시 상세 (Free+Pro+Premium) | USER / TIER:PRO / TIER:PREMIUM | `GET /disclosures/{id}`, `GET /disclosures/{id}/analysis` |
| D18/m20 | `/disclosures/[id]` (피드백 모달) | 분석 피드백 | USER | `POST /analyses/{id}/feedback` |

### Zone E · 알림

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D14/m06 | `/notifications/settings` | 알림 설정 | USER | `GET/PUT /notifications/settings` |
| D25/m08 | `/notifications/settings` (미리보기) | 알림톡 미리보기 | USER | 정적 |
| D23/m25 | 전역 팝오버/시트 | 알림 모달/시트 | USER | `GET /notifications?size=4` |
| D24/m26 | `/notifications` | 알림 센터 | USER | `GET /notifications`, `PATCH /notifications/{id}/read` |

### Zone F · 결제

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D21/m18 | 전역 모달 | Pro 업셀 모달 | USER | 없음 (UI mockup) |
| D19/m19 | `/checkout` | 결제 (카드 등록됨) | USER | MVP mockup |
| D20/m19b | `/checkout/new` | 결제 (신규 카드) | USER | MVP mockup |
| D20b/m19c | `/checkout/kakaopay` | 카카오페이 | USER | MVP mockup |

### Zone G · 계정

| ID | 라우트 | 화면 | 접근 | 주요 API |
|----|--------|------|------|------|
| D26/m09 | 전역 드롭다운/드로어 | 계정 메뉴 | USER | `GET /users/me` |
| D16/m24 | `/settings` | 마이페이지·설정 | USER | `GET /users/me`, `PATCH /users/me` |
| D22/m21 | `/share` | 공유 카드 | USER | `GET /users/me/share-summary` (확인 필요) |

---

## Tech Review (dc-tech-review · 2026-06-09)

### 아키텍처 분해

- **영향 레이어**: frontend 전용. 백엔드 코드 변경 없음.
- **신규**: Route Groups `(public)/(auth)/(app)`, layout 컴포넌트 8종, 도메인 컴포넌트 11종, API 레이어(lib/api/, lib/stores/, lib/schemas/), 신규 라우트 8개
- **수정**: 기존 스텁 라우트 10개 전면 교체, `layout.tsx`(폰트), `globals.css`(브랜드 토큰), `providers.tsx`(인증 Provider)
- **shadcn 스타일**: `base-nova` ([@base-ui/react](https://base-ui.com) 기반) 확인 완료 → `npx shadcn@latest add` CLI로 UI 컴포넌트 추가 가능. button.tsx 패턴과 일관성 유지됨.

### API 불일치 항목 (구현 전 백엔드 확인 필요)

| 항목 | Spec 가정 | api_spec.md 실제 | 조치 방향 |
|------|------|------|------|
| 알림 읽음 처리 | `PATCH /notifications/{id}/read` | **미존재** (db_schema에 `is_read` 컬럼 없음) | W6 진입 전 백엔드 엔드포인트 추가 OR 프론트 로컬 상태(Zustand)로 처리 결정 필요 |
| 공유 카드 요약 | `GET /users/me/share-summary` | **미존재** | W7 진입 전 백엔드 추가 OR 프론트 정적 집계 결정 |
| 이메일 OTP 인증 | `POST /auth/email/verify` | **미명시** (signup 흐름 내 암시만) | api_spec.md 보완 필요. 별도 엔드포인트 또는 signup 단계 내 포함인지 확인 |

### 작업 카드

| # | 작업 | Wave | 난이도 | 의존성 |
|---|------|------|--------|--------|
| T1 | 폰트 교체 + globals.css 브랜드 토큰 | W1 | 하 | - |
| T2 | Route Groups 구조 리팩터 (파일 이동 + `middleware.ts` 인증 가드) | W1 | 중 | - |
| T3 | shadcn CLI로 UI 기반 컴포넌트 추가 (`input, badge, card, dialog, sheet, tabs, switch, progress, popover`) | W1 | 하 | - |
| T4 | 레이아웃 컴포넌트 8종 구현 (AppShell, Sidebar, TopBar, BottomTabBar, HamburgerDrawer, AuthLayout, PublicNavbar, MobileShell) | W1 | 상 | T1·T3 |
| T5 | 도메인 컴포넌트 기반 4종 (SentimentBadge, ConfidenceMeter, DisclaimerNotice, TierGate) | W1 | 중 | T3 |
| T6 | 도메인 컴포넌트 심화 3종 (DisclosureCard Progressive Disclosure, FeedbackPrompt, OTPInput) | W1 | 중 | T5 |
| T7 | 도메인 컴포넌트 복합 3종 + 차트 (StockSearchCombobox, PlanCard, ProUpsellModal, PriceReactionChart) | W1 | 중 | T5 |
| T8 | API 레이어 기반 (lib/api/client.ts, 타입·훅 5종, Zustand stores 2종, Zod schemas 3종) + auth 전략 결정 | W1 | 중 | T2 |
| T9 | 랜딩 페이지 D1/m01 (히어로 + 기능 그리드 + 면책 고지) | W2 | 중 | T4 |
| T10 | 요금제 페이지 D4/m07 (PlanCard 3종 + 현재 등급 바인딩) | W2 | 중 | T4·T7 |
| T11 | 로그인·가입 D5/m02 (소셜·이메일 분기 + auth 설정) | W3 | 상 | T4·T8 |
| T12 | 이메일 인증 D6/m10 (OTPInput + 타이머 + 재전송) | W3 | 중 | T6·T11 |
| T13 | 약관 동의 D7/m11 (체크 그룹 + 자본시장법 고지 + 동의 로깅) | W3 | 중 | T11 |
| T14 | 휴대폰 인증 D8/m12 (번호 입력 + SMS OTP + 스킵) | W3 | 중 | T12 |
| T15 | 프로필 입력 D9/m13 (라디오 + 세그먼트 + 선택 완료) | W3 | 하 | T13 |
| T16 | 가입 완료 D10/m14 + 빈 대시보드 Empty state D11/m15 | W3 | 하 | T15 |
| T17 | 대시보드 D2/m03 (통계 4종 + DisclosureCard 피드 + Progressive Disclosure) | W4 | 상 | T4·T6·T8 |
| T18 | 공시 피드 D15/m23 (필터 칩 + 날짜 그룹 + Pro TierGate) | W4 | 중 | T5·T17 |
| T19 | 공시 상세 Free+Pro D3/m04 (판정 배지·근거·요약·유사사례 차트·신뢰도·DART 원문 링크) | W4 | 상 | T5·T7·T18 |
| T20 | 공시 상세 Premium D17/m17 (재무 테이블·업황 비교) + 피드백 D18/m20 | W4 | 중 | T5·T19 |
| T21 | 종목 관리 D12/m05 + 검색 자동완성 D12b/m05b (StockSearchCombobox + 쿼터 바) | W5 | 중 | T7·T4 |
| T22 | 종목 상세 등록 D13/m22 (매수가·수량 입력 + 알림 토글 + 저장) | W5 | 중 | T21 |
| T23 | 알림 설정 D14/m06 (채널·유형·야간 토글) + 알림톡 미리보기 D25/m08 | W6 | 중 | T4·T8 |
| T24 | 알림 모달/시트 D23/m25 (전역 팝오버·시트, 읽음 처리 방식 결정 후) | W6 | 중 | T3·T23 |
| T25 | 알림 센터 D24/m26 (날짜 그룹 + 필터 + 읽음 처리) | W6 | 중 | T24 |
| T26 | Pro 업셀 모달 D21/m18 (ProUpsellModal 전역 Context + TierGate 연결) | W7 | 중 | T5·T7 |
| T27 | 결제 UI mockup D19/D20/D20b (카드 등록·신규·카카오페이 — 비연동 static) | W7 | 중 | T4·T26 |
| T28 | 계정 메뉴 D26/m09 (드롭다운·드로어) + 마이페이지·설정 D16/m24 | W7 | 중 | T4·T8 |
| T29 | 공유 카드 D22/m21 (이미지 카드 + 다운로드/공유 — share-summary API 확인 후) | W7 | 중 | T28 |

### 파생 이슈 (구현 시작 전 해결 필요)

1. **인증 전략 결정 (T8 전제)**: NextAuth.js v5 credentials provider(백엔드 JWT 연동) vs 순수 Zustand + httpOnly cookie 방식. 선택 기준: 소셜 OAuth 콜백 처리 편의성. 권장: NextAuth v5 (`pnpm add next-auth@beta`), 백엔드 자체 JWT를 credentials provider로 래핑.

2. **Pretendard 폰트 (T1 전제)**: Google Fonts 미지원. 옵션:
   - `pnpm add pretendard` → `next/font/local` 로드
   - CDN `@font-face` in globals.css (캐시 의존)
   → **권장**: `pnpm add pretendard` + `next/font/local`. IBM Plex Mono는 `next/font/google` 사용.

3. **알림 읽음 처리 (T24 전제)**: db_schema `notifications` 테이블에 `is_read` 컬럼 없음. FE 전용 Zustand local state로 임시 처리 후 백엔드 컬럼 추가 시 API 연동으로 전환하는 2-step 접근 권장.

### DB / 마이그레이션 영향

**없음** — 프론트엔드 전용 작업. DB 변경 불필요.

단, 알림 읽음 처리를 백엔드와 연동하려면 향후 별도 Spec에서 `V{n}__add_is_read_to_notifications.sql` 마이그레이션이 필요할 수 있음.

### 외부 계약 영향

없음 — DART/KRX/카카오 API 연동 없음. 결제 UI는 MVP mockup까지만.

### 리스크 & 법적 검토

| 리스크 | 심각도 | 대응 |
|--------|--------|------|
| `DisclaimerNotice` 누락 | **P0** | 공시 상세(T19·T20)·알림(T23~T25) 모든 화면에 `DisclaimerNotice` 필수. dc-review-code 게이트에서 검증 |
| 투자 권유 카피 노출 | **P0** | T9(랜딩)·T11(가입) 텍스트 전수 검토. "매수/매도 추천", "꼭 사세요" 금지 |
| `is_withheld=true` 미처리 | **P1** | T19에서 `ConfidenceMeter`가 임계 미만 시 호재/악재 배지 숨김·판단보류 표시 구현 필수 |
| 매수가 평문 콘솔 로그 | **P1** | T22 `portfolioSchemas.ts` 폼: Sentry 마스킹 설정, console 출력 배제 |
| 색맹 대비 미흡 | **P1** | `SentimentBadge`(T5) 색+아이콘+텍스트 3중 표기. dc-review-frontend 접근성 체크 |
| NextAuth v5 Beta 안정성 | **P2** | Beta 버전 사용. 주요 breaking change 발생 시 순수 Zustand 방식으로 전환 가능하도록 auth 레이어를 `lib/auth.ts`에 캡슐화 |

### 예상 Wave 수

**7 Wave, 29 작업 카드** — Wave별 PR 권장.
- W1 (T1~T8): 기반 — 가장 중요, 이후 Wave 모두 의존
- W2 (T9~T10): 랜딩·요금제 — 독립적, W1 이후 즉시 가능
- W3 (T11~T16): 온보딩 — auth 전략 결정 후
- W4 (T17~T20): 핵심 — W3 로그인 완료 후
- W5 (T21~T22): 종목 — W4 대시보드 완료 후
- W6 (T23~T25): 알림 — W4 병행 가능, 읽음 처리 방식 결정 필요
- W7 (T26~T29): 결제·계정 — W4 완료 후
