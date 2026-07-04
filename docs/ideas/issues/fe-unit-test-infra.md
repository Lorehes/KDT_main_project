---
type: issue
status: Closed
created: 2026-06-23
updated: 2026-06-25
resolved: 2026-06-25
---

# [이슈] FE 단위 테스트 인프라 미구성 (Vitest + Testing Library)

> 상태: **Closed** — 2026-06-25 해결.
>
> **해결 내용**: `@testing-library/react`, `@testing-library/user-event`, `@testing-library/jest-dom`, `@vitejs/plugin-react` 설치.
> `vitest.config.ts`에 `plugins: [react()]`, `setupFiles: ["./src/test/setup.ts"]` 추가.
> `src/test/setup.ts` 생성(`import "@testing-library/jest-dom"`).
> `src/lib/__tests__/isActivePath.test.ts` 작성 (15개 케이스, 58개 전체 테스트 통과).
> `pnpm test:unit`, `pnpm typecheck` 모두 통과 확인.

## 현상

`frontend/package.json`에 Vitest 및 Testing Library가 설치되어 있지 않다.
`pnpm vitest run` 실행 시 `Command "vitest" not found` 에러 발생.

현재 FE 테스트 커버리지:

| 방법 | 현황 |
|------|------|
| 단위/컴포넌트 테스트 (Vitest + Testing Library) | ❌ 미설치 |
| TypeScript 타입체크 (`pnpm typecheck`) | ✅ 동작 |
| E2E (Playwright) | ✅ 설치됨 (`@playwright/test`) |

`src/` 내 `*.test.*` / `*.spec.*` 파일: **0개**

## 발견 경위

`be-api-alignment-mvp-r1` Spec 구현 후 `/dc-test-verify` 실행 시 확인 (2026-06-23).  
`signup/page.tsx`의 `aria-label` 수정 검증이 typecheck로만 종료됨.

## 영향 범위

- `frontend/src/` 전체 컴포넌트 및 훅 — 단위 검증 불가
- 특히 취약한 영역:
  - `src/lib/` 유틸 함수 (API 클라이언트, 날짜 포맷, 그룹핑 로직)
  - `src/components/` UI 컴포넌트 (배지, 카드, 폼 등)
  - Zustand 스토어 상태 전이
  - React Hook Form + Zod 검증 로직
- FE 변경 후 `/dc-test-verify` 결과가 "typecheck만 통과"로 기록됨 → 실질 검증력 부족

## 현재 스택 (package.json 기준)

```
React 19.1.0 / Next.js 15.5.18 (App Router) / TypeScript 5
TanStack Query 5 / Zustand 5 / React Hook Form 7 / Zod 4
Playwright 1.60 (E2E — 설치됨)
```

## 해결 방향

### 필요 패키지 (devDependencies)

```bash
pnpm add -D vitest @vitest/coverage-v8 \
  @testing-library/react @testing-library/user-event @testing-library/jest-dom \
  jsdom
```

> React 19 + Next.js 15 App Router 환경에서는 `jsdom` 환경 + `@vitejs/plugin-react` 필요.
> `happy-dom`도 대안이나 jsdom이 Testing Library와 호환성 안정적.

### vitest.config.ts 최소 설정

```ts
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
  },
})
```

### package.json 스크립트 추가

```json
"test": "vitest run",
"test:watch": "vitest",
"test:coverage": "vitest run --coverage"
```

### 우선 작성 대상 테스트

| 우선순위 | 대상 | 이유 |
|----------|------|------|
| P0 | `src/lib/` 유틸 (날짜 포맷, groupByDate, API 응답 파싱) | 순수 함수 — 테스트 작성 비용 낮음, 회귀 리스크 高 |
| P1 | Zod 스키마 (`src/lib/schemas/`) | 폼 검증 오류 방지 |
| P2 | 핵심 컴포넌트 (공시 배지, 신뢰도 표시) | 접근성·배지 로직 변경 시 회귀 방지 |
| P3 | Zustand 스토어 | 상태 전이 검증 |

## 리스크

- React 19 + Next.js 15 App Router는 Vitest 설정이 표준 CRA보다 복잡 (Server Component mocking 필요)
- `@testing-library/react` v15+는 React 19를 지원하지만, `act()` 경고가 발생할 수 있음 — setup 파일에서 억제 필요 여부 확인
- Next.js 내부 모듈 (`next/navigation`, `next/image` 등) mock 설정 별도 필요

## 다음 단계

1. `/dc-tech-review fe-unit-test-infra` — 작업 카드 분해 (설정 + 초기 테스트 작성 범위 확정)
2. Spec 승격 후 `/dc-implement` — 인프라 설정 + P0 유틸 테스트 작성
3. `/dc-test-verify` — 설치 후 전체 테스트 실행 확인

## 관련

- [[be-api-alignment-mvp-r1]] — 이슈 발견 계기 (2026-06-23)
- CLAUDE.md §6-6 — 테스트 커버리지 요구사항 (FE: Vitest + Testing Library + Playwright)
