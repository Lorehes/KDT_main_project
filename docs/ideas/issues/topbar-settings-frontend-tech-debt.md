---
type: issue
status: Closed
created: 2026-06-22
updated: 2026-06-25
resolved: 2026-06-25
source: dc-review-code (TopBar/Settings/PublicMobileMenu M2 리뷰)
priority: P2
---

# TopBar · Settings · PublicMobileMenu 프론트엔드 기술 부채

> **상태**: Closed — 2026-06-25 전체 처리 완료.
>
> - #1 TIER_LABEL 중앙화: `constants.ts`에 TIER_LABEL·TIER_LABEL_LONG·TIER_PRICE 추가, 로컬 선언 3곳 제거
> - #2 NAV_ITEMS 중앙화: `lib/navigation.ts` 신규, APP_NAV_ITEMS·PUBLIC_NAV_ITEMS 5곳 import 교체
> - #3 Sidebar.tsx 삭제: 참조 0건 확인 후 삭제
> - #4 Zustand 셀렉터 최적화: TopBar 필드별 셀렉터 분리
> - #5 임의 px 토큰 위반: 디자인 명세 확인 필요 — 이번 범위 외 skip
> - #6 AppShell 이중 폴링: `useUnreadCount`가 refetchInterval 없음(staleTime만) → 실질 이중 폴링 없음 — skip
> - #7 PublicMobileMenu aria-controls: `aria-controls="public-mobile-nav"` + SheetContent `id` 추가
> - #8 Bell aria-label: 동적 미읽음 카운트 반영 — 이미 처리됨 확인
> - #9 APP_VERSION env: `next.config.ts`에 `NEXT_PUBLIC_APP_VERSION` 추가, settings에서 사용
> - #10 isActivePath boundary: `startsWith(href + "/")` 수정 + 테스트 작성

## 배경

2026-06-22 TopBar 프로필 팝오버 + 사이드바 제거 + 마이페이지 레이아웃 재구성 + PublicMobileMenu 신규 구현 이후 dc-review-code (5-에이전트) 리뷰에서 도출된 아키텍처·성능·접근성 기술 부채 목록.

즉시 수정 항목(버그·WCAG·에러 처리)은 동일 세션 dc-implement로 처리됨.
이 문서는 **구조적 리팩터링·성능 최적화 항목**을 다룬다.

---

## 1. TIER_LABEL / TIER_PRICE 4곳 중복 선언

### 현황
| 파일 | 내용 | 형태 |
|------|------|------|
| `frontend/src/components/layout/TopBar.tsx:21` | FREE/PRO/PREMIUM 장문 레이블 | `Record<string, string>` |
| `frontend/src/app/(app)/settings/page.tsx:19` | FREE/PRO/PREMIUM 단문 레이블 | `Record<string, string>` |
| `frontend/src/components/domain/TierGate.tsx:22` | PRO/PREMIUM만, 단문 | `Record<"PRO"\|"PREMIUM", string>` |
| `frontend/src/app/(app)/settings/page.tsx:25` | TIER_PRICE (₩표기) | `Record<string, string>` |

현재 동일 사용자에게 TopBar 팝오버에는 "PRO 플랜 · 무제한 종목", 설정 페이지에는 "Pro 플랜"으로 불일치 표시.

### 목표
`frontend/src/lib/constants.ts`에 단일 export:
```ts
export const TIER_LABEL: Record<string, string> = {
  FREE:    "Free",
  PRO:     "Pro",
  PREMIUM: "Premium",
};
export const TIER_LABEL_LONG: Record<string, string> = {
  FREE:    "Free 플랜",
  PRO:     "Pro 플랜 · 무제한 종목",
  PREMIUM: "Premium 플랜 · 모든 기능",
};
export const TIER_PRICE: Record<string, string> = {
  FREE:    "무료",
  PRO:     "₩9,900/월",
  PREMIUM: "₩29,900/월",
};
```
각 컴포넌트에서 로컬 선언 제거 후 import.

### 영향 파일
- `frontend/src/components/layout/TopBar.tsx`
- `frontend/src/app/(app)/settings/page.tsx`
- `frontend/src/components/domain/TierGate.tsx`
- `frontend/src/lib/constants.ts` (추가)

---

## 2. NAV_ITEMS 4곳 + Public NAV_ITEMS 2곳 중복

### 현황 — 앱 내부 Nav (인증 후)
| 파일 | 항목 | 차이 |
|------|------|------|
| `TopBar.tsx:34` | dashboard/disclosures/portfolios | icon: LayoutDashboard/FileText/Briefcase |
| `Sidebar.tsx:24` | 동일 | 동일 구조 |
| `HamburgerDrawer.tsx:16` | 동일 | 동일 구조 |
| `BottomTabBar.tsx:17` | 동일 href, 레이블 단축(공시/종목) | label 다름 |

### 현황 — 퍼블릭 Nav (미인증)
| 파일 | 항목 |
|------|------|
| `PublicNavbar.tsx:31` | inline JSX 4개 Link (배열 아님) |
| `PublicMobileMenu.tsx:17` | NAV_ITEMS 배열 4개 |

### 목표
`frontend/src/lib/navigation.ts` 신규 생성:
```ts
// 앱 내부 (인증 후) — TopBar·Sidebar·HamburgerDrawer 공용
export const APP_NAV_ITEMS = [
  { href: "/dashboard",   label: "대시보드",    labelShort: "대시보드", icon: LayoutDashboard },
  { href: "/disclosures", label: "공시 피드",   labelShort: "공시",    icon: FileText },
  { href: "/portfolios",  label: "내 포트폴리오", labelShort: "종목",   icon: Briefcase },
] as const;

// 퍼블릭 (미인증) — PublicNavbar·PublicMobileMenu 공용
export const PUBLIC_NAV_ITEMS = [
  { href: "/#features", label: "기능" },
  { href: "/pricing",   label: "요금제" },
  { href: "/#cases",    label: "고객사례" },
  { href: "/#help",     label: "도움말" },
] as const;
```
BottomTabBar는 `labelShort` 필드 사용.

### 영향 파일
- `frontend/src/lib/navigation.ts` (신규)
- `frontend/src/components/layout/TopBar.tsx`
- `frontend/src/components/layout/Sidebar.tsx`
- `frontend/src/components/layout/HamburgerDrawer.tsx`
- `frontend/src/components/layout/BottomTabBar.tsx`
- `frontend/src/components/layout/PublicNavbar.tsx`
- `frontend/src/components/layout/PublicMobileMenu.tsx`

### 주의사항
- BottomTabBar는 `labelShort` 필드 사용하여 레이블 차이 유지
- PublicNavbar 데스크톱 nav는 배열 map으로 전환 필요 (현재 inline JSX)
- RSC(PublicNavbar)에서 import 가능 — 배열은 순수 데이터

---

## 3. Sidebar.tsx 데드코드

### 현황
`AppShell.tsx`에서 Sidebar import 제거 후 `Sidebar.tsx`를 아무 파일도 import하지 않음.
파일 내 `NAV_ITEMS` 배열(위 #2에 포함)이 살아 있어 혼란 유발.

### 목표
`Sidebar.tsx` 삭제.
AppShell.tsx 주석 `웹(Sidebar+TopBar)` → `웹(TopBar+Main)` 업데이트.

### 주의사항
삭제 전 `git grep "from.*Sidebar"` 로 추가 참조 없음 확인 필요.

---

## 4. Zustand store selector 없는 TopBar 전체 리렌더

### 현황
```tsx
// TopBar.tsx:42
const { user, isLoading, logout } = useAuthStore();    // 전체 구독
const { toggleNotifModal } = useUIStore();              // 전체 구독
```
`authStore`의 어떤 필드(isLoading, drawer 등)가 바뀌어도 TopBar 전체가 리렌더됨.
TopBar는 모든 인증 페이지에 항상 마운트 — 영향 범위 최대.

### 목표
```tsx
const user             = useAuthStore(s => s.user);
const isLoading        = useAuthStore(s => s.isLoading);
const logout           = useAuthStore(s => s.logout);
const toggleNotifModal = useUIStore(s => s.toggleNotifModal);
```
함께 개선: `useMemo`로 `initials`, `tierLabel`, `activeMap(pathname)` 메모이제이션.

### 영향 파일
- `frontend/src/components/layout/TopBar.tsx`

---

## 5. 임의 px 값 CLAUDE.md §6.4 디자인 토큰 위반

### 현황 (TopBar.tsx)
`text-[15px]`, `text-[13.5px]`, `text-[11px]`, `text-[10px]`,
`rounded-[10px]`, `rounded-[11px]`, `size-[38px]`, `size-[42px]`

동일 패턴이 Sidebar.tsx, HamburgerDrawer.tsx에도 존재.
CLAUDE.md §6.4: `#hex`, 임의 `px` 직접 입력 금지.

### 목표
`frontend/src/app/globals.css` (또는 tailwind config)에 시멘틱 토큰 정의:
```css
/* globals.css 또는 tailwind.config.ts theme.extend */
--radius-nav-item: 10px;
--radius-control: 11px;
--size-avatar-sm: 38px;
--size-avatar-md: 42px;
```
또는 `tailwind.config.ts`의 `theme.extend.fontSize/spacing/borderRadius`에 명명:
```ts
fontSize: { '2xs': '10px', 'xs-plus': '11px', 'sm-tight': '13.5px', 'base-tight': '15px' }
borderRadius: { 'nav': '10px', 'control': '11px' }
```

### 영향 파일
- `frontend/src/components/layout/TopBar.tsx`
- `frontend/src/components/layout/Sidebar.tsx`
- `frontend/src/components/layout/HamburgerDrawer.tsx`
- `frontend/src/app/globals.css` 또는 `tailwind.config.ts` (신규 토큰)

### 주의사항
토큰 이름 확정 전 디자인 명세서(`design_structure.md`)의 8pt 그리드 규칙 확인 필요.

---

## 6. AppShell 모바일·웹 레이아웃 동시 마운트 — useUnreadCount 이중 폴링

### 현황
AppShell이 CSS `hidden/md:flex`로 두 레이아웃을 동시 DOM에 마운트.
- 데스크톱: `TopBar`(useUnreadCount 30s 폴링) 마운트
- 모바일: `MobileAppBar`(TopBar 없음) 마운트 → TopBar는 hidden이지만 폴링 중

결과: 모바일에서도 TopBar의 useUnreadCount 쿼리가 30초마다 BE 호출.

### 목표
`useUnreadCount` 훅을 AppShell 레벨의 공유 Provider로 이동 또는
`NotificationContext`로 추출 → TopBar와 MobileAppBar 둘 다 context에서 읽어 폴링 중복 방지.

단기 대안: useUnreadCount에 `enabled: !isMobile` 조건 추가 (useMediaQuery 훅 필요).

### 영향 파일
- `frontend/src/components/layout/AppShell.tsx`
- `frontend/src/components/layout/TopBar.tsx`
- `frontend/src/components/layout/MobileAppBar.tsx` (있으면)
- 신규: `frontend/src/lib/context/NotificationContext.tsx`

### 주의사항
SSR에서 `window.innerWidth` 접근 불가 — hydration mismatch 주의.
단기 대안이 더 안전함.

---

## 7. PublicMobileMenu aria-controls 미선언

### 현황
```tsx
// PublicMobileMenu.tsx:34
<button aria-expanded={open}>  // aria-controls 없음
<Sheet open={open}>
  <SheetContent>  // id 없음
```
트리거 버튼과 Sheet 패널이 AT에 관계 미선언. WCAG 4.1.2 미준수.

### 목표
```tsx
<button aria-expanded={open} aria-controls="public-mobile-nav">
<SheetContent id="public-mobile-nav">
```

### 영향 파일
- `frontend/src/components/layout/PublicMobileMenu.tsx`

---

## 8. Bell 버튼 미읽음 카운트 aria-label 미반영

### 현황
```tsx
// TopBar.tsx:88 (추정)
<button aria-label="알림 열기">
  {/* 내부 span에 aria-label="미읽음 N건" — 버튼 accessible name에 포함 안됨 */}
```
버튼의 accessible name이 "알림 열기"만이어서 스크린리더가 미읽음 개수를 알 수 없음.

### 목표
```tsx
aria-label={unreadCount ? `알림 열기 (미읽음 ${unreadCount}건)` : "알림 열기"}
```

### 영향 파일
- `frontend/src/components/layout/TopBar.tsx`

---

## 9. APP_VERSION 하드코딩

### 현황
`settings/page.tsx:17`: `const APP_VERSION = "0.1.0"` — package.json과 수동 동기화 필요.

### 목표
`next.config.ts`:
```ts
env: { NEXT_PUBLIC_APP_VERSION: process.env.npm_package_version }
```
컴포넌트에서 `process.env.NEXT_PUBLIC_APP_VERSION ?? "0.0.0"` 사용.

### 영향 파일
- `frontend/next.config.ts` (또는 `next.config.js`)
- `frontend/src/app/(app)/settings/page.tsx`

---

## 10. isActivePath startsWith 경계 미검사

### 현황
`frontend/src/lib/utils/isActivePath.ts`:
```ts
pathname.startsWith(href) // "/portfolios-v2" → /portfolios nav 활성 오매칭 가능
```

### 목표
```ts
pathname === href || pathname.startsWith(href + "/")
```

### 영향 파일
- `frontend/src/lib/utils/isActivePath.ts`

---

## 우선순위 & 예상 공수

| # | 이슈 | 우선순위 | 공수 | 의존 |
|---|------|---------|------|------|
| 1 | TIER_LABEL 중앙화 | P2 | 1h | — |
| 2 | NAV_ITEMS 중앙화 | P2 | 2h | #3 |
| 3 | Sidebar.tsx 삭제 | P2 | 0.5h | — |
| 4 | Zustand 셀렉터 + useMemo | P2 | 1h | — |
| 5 | 디자인 토큰 임의 px | P3 | 2h | 디자인명세 확인 |
| 6 | AppShell 이중 폴링 | P2 | 2h | — |
| 7 | PublicMobileMenu aria-controls | P2 | 0.5h | — |
| 8 | Bell aria-label | P2 | 0.5h | — |
| 9 | APP_VERSION env | P3 | 0.5h | next.config |
| 10 | isActivePath boundary | P2 | 0.5h | — |

**총 예상 공수**: ~10.5h → 1~2 sprint (P2 묶음 / P3 별도)

---

## 관련 이슈
- [[public-layout-dynamic-rendering-perf]] — AppShell 이중 마운트와 연관
- [[public-navbar-aria-labels]] — PublicMobileMenu aria-controls와 연관
