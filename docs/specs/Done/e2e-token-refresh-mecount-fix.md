---
type: spec
status: Done
created: 2026-06-23
updated: 2026-06-23
---

# E2E 토큰 갱신 테스트 픽스 — meCallCount 오차 수정

> 상태: Draft → **Done** (2026-06-23, concurrent-auth 픽스처 1줄 조건부 마운트 수정)
> 관련 Spec: [[oauth-consent-enforcement]] (middleware 변경 후 dc-test-verify에서 발견)
> 발견 경위: 2026-06-23 dc-test-verify 실행 — stash 검증으로 기존 결함 확인

---

## 배경 / 목적

`e2e/auth/token-refresh.spec.ts` `(a)` 테스트가 `meCallCount === 10`을 기대하지만
실제로는 **11**을 반환해 지속적으로 실패한다.

- **근본 원인**: 테스트 픽스처 페이지(`/test/concurrent-auth`)가 `<AuthBroadcastListener />`를
  항상 마운트한다. `AuthBroadcastListener.useEffect`가 마운트 시 `fetchMe()` → `/users/me`를 **1회
  추가 호출**하여 모킹 카운터에 집계된다.
- **타이밍**: `fetchMe()` 호출이 테스트의 5개 병렬 호출과 Promise 큐를 공유 → 결과적으로
  초기 401 건수와 재시도 건수가 각 1씩 증가해 총 11회.
- **우리 변경과 무관**: `git stash` 후 우리 E4 미들웨어 변경 이전 상태에서도 동일하게 실패.

---

## 해결 방안

### 선택된 방안 — B: 픽스처 페이지에서 `mode=concurrent` 시 `AuthBroadcastListener` 조건부 제외

| 방안 | 설명 | 판단 |
|------|------|------|
| A. 어설션 완화 | `toBe(10)` → `toBeGreaterThanOrEqual(10)` | ❌ 카운트 의미 소실, 회귀 감지 불가 |
| **B. 조건부 마운트** | `mode !== "concurrent"`일 때만 `<AuthBroadcastListener />` 렌더 | ✅ 최소 변경, 의미 유지 |
| C. 베이스라인 차분 | 호출 전 카운터 스냅샷 후 delta 10 검증 | △ 비동기 타이밍 복잡 |
| D. 별도 픽스처 페이지 | `/test/promise-queue` 신설 | △ 과잉 — 1줄 조건으로 해결 가능 |

**B 선택 이유**: `(a)` 테스트는 Promise 큐 동작을 검증하며 BroadcastChannel이 불필요.
`(b)·(c)` 테스트는 `mode` 파라미터 없이 페이지를 방문하므로 `AuthBroadcastListener`가 유지된다.
픽스처 페이지 1줄 수정으로 모든 케이스가 동작한다.

---

## 변경 범위

### 1. `frontend/src/app/test/concurrent-auth/page.tsx`

`<AuthBroadcastListener />`를 `mode !== "concurrent"` 조건으로 감싼다.

```tsx
// 변경 전
return (
  <>
    <AuthBroadcastListener />
    <div data-testid="status">{status}</div>
  </>
);

// 변경 후
return (
  <>
    {mode !== "concurrent" && <AuthBroadcastListener />}
    <div data-testid="status">{status}</div>
  </>
);
```

**근거**: `mode=concurrent`는 Promise 큐 전용 픽스처 모드 — `fetchMe()` 초기화가 개입하면
안 된다. `mode`가 없는 일반 방문(BroadcastChannel 테스트)은 `AuthBroadcastListener`가
필요하므로 유지.

### 2. `frontend/e2e/auth/token-refresh.spec.ts`

어설션은 그대로 유지 (`expect(meCallCount).toBe(10)`). 픽스처 수정만으로 통과.

---

## 검증 기준

| 항목 | 기대 결과 |
|------|-----------|
| `(a)` meCallCount | 10 (초기 401 5건 + refresh 후 재시도 200 5건) |
| `(a)` refreshCallCount | 1 (Promise 큐 — 1회 refresh 보장) |
| `(a)` status | `"done:5:0"` (5개 모두 최종 성공) |
| `(b)` refresh 실패 | `/login` 리다이렉트 유지 |
| `(c)` BroadcastChannel | 탭 B `/login` 이동 유지 |
| `(c)` localStorage 폴백 | 탭 B `/login` 이동 유지 |
| `AuthBroadcastListener` 마운트 | mode 없는 경우 정상 마운트 확인 |

---

## 영향 범위

- **변경 파일**: `frontend/src/app/test/concurrent-auth/page.tsx` 1줄
- **런타임 영향**: 없음 — 테스트 전용 픽스처 페이지 (프로덕션에서 `null` 반환)
- **다른 E2E 테스트**: `(b)·(c)`는 `mode` 파라미터 없이 방문 → `AuthBroadcastListener` 유지 → 영향 없음

---

## 리스크

- **낮음**: 1줄 조건 수정, 프로덕션 코드 무변경
- `(c)` BroadcastChannel 테스트가 `TEST_PAGE_LISTENER = "/test/concurrent-auth"` (mode 없음)를
  사용하므로 `AuthBroadcastListener`가 여전히 마운트됨을 확인해야 함
