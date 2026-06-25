---
type: issue
status: open
severity: medium
created: 2026-06-22
updated: 2026-06-22
source: dc-review-code (pricing-nav-auth-consistency)
---

# (public) 레이아웃 동적 렌더링 전환 — TTFB 성능 이슈

> 상태: **open** (후속 최적화 과제, 현행 머지 블로커 아님)
> 발견: `pricing-nav-auth-consistency` 코드 리뷰 Medium 이슈
> 관련: [[pricing-nav-auth-consistency]]

## 배경 / 원인

`pricing-nav-auth-consistency` Spec 구현에서 `(public)/layout.tsx`에 `cookies()` 호출을 추가해
로그인 사용자에게 올바른 네비바 CTA("대시보드로")를 제공했다.

```ts
// frontend/src/app/(public)/layout.tsx:15
const isAuthenticated = Boolean((await cookies()).get("dr_session"));
```

Next.js 15에서 `cookies()`는 **동적 데이터 소스**로 취급되어, 이 레이아웃 하위 모든 라우트가
**정적 캐싱(ISR/SSG) 불가** 상태로 전환된다.

## 영향 범위

| 항목 | 상태 |
|------|------|
| 영향 라우트 | `(public)` 그룹 전체 — 현재 `/pricing` 하나 |
| 랜딩(`/`) | **비영향** — `app/page.tsx`(루트 레이아웃, `cookies()` 미사용) |
| 성능 영향 | `/pricing` HTML 셸 per-request 렌더링, CDN 캐시 불가 |

### 현 상황이 왜 실질적 영향이 낮은가

`/pricing` 페이지는 `PricingClient.tsx`를 포함하며, 이 클라이언트 컴포넌트가 BE의
`/pricing/plans` API를 TanStack Query로 페칭한다. 즉 **이미 JS hydration이 필수**이므로
HTML 셸 자체의 정적/동적 여부는 체감 성능에 영향이 미미하다.

문제가 커지는 시점: `(public)` 그룹에 완전 정적화 가능한 콘텐츠 페이지(공지, 약관, FAQ 등)가
다수 추가되는 경우.

## 해결 방향

### A안 (권장 — 트래픽이 실제 문제가 될 시점에 전환)

`(public)/layout.tsx`를 정적으로 유지하고, 네비바 CTA 전환 로직을 클라이언트 컴포넌트로 이동.

```tsx
// (public)/layout.tsx — async/cookies() 제거, 정적 유지
export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <PublicNavbar />          {/* isAuthenticated prop 제거 */}
      <main className="flex-1">{children}</main>
    </div>
  );
}
```

```tsx
// PublicNavbar.tsx — "use client" 복원, useAuthStore 구독
"use client";
export function PublicNavbar() {
  const { user } = useAuthStore();
  // user는 클라이언트 nav 시 메모리 유지, 하드리프레시 시 null→store 재hydrate
  return (
    <header ...>
      {user ? (
        <Link href="/dashboard" aria-label="대시보드로 이동">대시보드로 →</Link>
      ) : (
        <>
          <Link href="/login">로그인</Link>
          <Link href="/signup">무료로 시작</Link>
        </>
      )}
    </header>
  );
}
```

**트레이드오프**: 하드리프레시 시 로그인 사용자도 잠깐 비로그인 CTA 노출 후 JS hydration 완료
시점에 "대시보드로" 전환(플리커). `(app)` 사이드바 → `/pricing` 클라이언트 nav 경로는
Zustand store 메모리 유지로 즉시 정상 표시.

### B안 — Suspense 경계로 점진 렌더

```tsx
// (public)/layout.tsx — Suspense fallback은 비로그인 네비바
export default function PublicLayout({ children }) {
  return (
    <div className="flex min-h-screen flex-col">
      <Suspense fallback={<PublicNavbar isAuthenticated={false} />}>
        <AuthAwareNavbar />
      </Suspense>
      <main className="flex-1">{children}</main>
    </div>
  );
}

// AuthAwareNavbar.tsx — "use client"
function AuthAwareNavbar() {
  const { user } = useAuthStore();
  return <PublicNavbar isAuthenticated={Boolean(user)} />;
}
```

A안과 동일한 플리커 이슈 존재. 코드 복잡도만 추가.

### C안 (현행 유지)

현재 구현(`cookies()` prop 방식)을 유지. `/pricing`은 어차피 API 페칭이 있어
per-request 렌더링 비용이 실질적 문제가 되지 않는 현 단계에서는 가장 단순한 선택.
**MVP 완료·MAU 1만 달성 전까지 C안(현행 유지) 권장.**

## 채택 권고

**현재 → C안(현행 유지).** MAU/트래픽 데이터 확보 후 TTFB가 측정 가능한 문제가 되는
시점에 A안으로 전환. 사이드바→`/pricing` 클라 nav가 주된 사용 패턴인 현 상황에서
하드리프레시 플리커(A안 약점)보다 서버 per-request 오버헤드(C안 약점)가 더 낫다.

## 행동 기준 (전환 트리거)

- 전환 전 Vercel Analytics / Lighthouse CI에서 `/pricing` TTFB ≥ 500ms 지속 확인 시
- `(public)` 그룹에 정적화 적합 페이지(공지·FAQ·약관) 3개 이상 추가 시

## 관련 파일

- `frontend/src/app/(public)/layout.tsx:15` — `cookies()` 호출
- `frontend/src/components/layout/PublicNavbar.tsx` — CTA 분기 로직
- `frontend/src/app/(public)/pricing/PricingClient.tsx` — 클라이언트 API 페칭(이미 동적)
