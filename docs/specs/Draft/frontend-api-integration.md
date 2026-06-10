---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-10
---

# 프론트엔드 백엔드 API 실연동 Spec

> 상태: **Draft** (2026-06-10 재진단 — dc-tech-review 페이지별 Read 완료)

## 배경 / 목적

원래 Spec 전제("TanStack Query 훅은 타입만 완성, 실연동 미수행")는 실제 코드와 달랐다. 페이지별 Read 결과, R2~R8은 이미 구현되어 있고 **실제 잔여는 3항목**이다.

- **현황**: 모든 페이지가 TanStack Query 훅으로 실데이터를 호출 중. 단, `.env.local`의 URL에 `/api/v1` 경로 누락으로 환경변수 set 상태에서 404 위험. `fetchMe()` 부트스트랩이 루트(`/`) 진입에만 동작. 전역 Toast 미도입.
- **목표**: P0 URL prefix 정합 + 직접 진입 시 user 복원 + 전역 에러 Toast 표준화

---

## 요구사항

> ✅ = 코드 확인 완료 / ⚠️ = 수정 필요 / (2026-06-10 페이지별 Read 기준)

- [x] **R2** 401 refresh 인터셉터 — `client.ts` Promise-queue 기반, 5초 timeout·1회 재시도·logout 폴백 ✅
- [x] **R3** 대시보드 실데이터 — `useDisclosures({scope:"portfolio"})` + `usePortfolios()` 호출 중 ✅
- [x] **R4** 공시 피드 페이지네이션 — page state + 누적 + `canLoadMore` 가드 구현 ✅
- [x] **R5** 공시 상세 TierGate — `useDisclosure` + `useDisclosureAnalysis(enabled:!!disclosure)` 직렬화. isPro/isPremium 분기, `analysis?.similar_disclosures` 옵셔널 체이닝 ✅
- [x] **R6** 포트폴리오 mutation — `usePortfolios` + `useCreatePortfolio` + `useDeletePortfolio`. 422/409 인라인 에러 분기 완료 ✅
- [x] **R7** 알림 설정 — `useNotificationSettings` + `useUpdateNotificationSettings`. 서버 설정 → 로컬 state 동기화 완료. (`grep "mock" hit`는 `KakaoPreview` UI 정적 미리보기 주석, 데이터 mock 아님) ✅
- [x] **R8** 피드백 mutation — `/analyses/{id}/feedback` POST 훅 존재 ✅
- [ ] **R1** `NEXT_PUBLIC_API_URL` prefix 정합 — `.env.local`·`.env.example` 값 `http://localhost:8080` → `http://localhost:8080/api/v1` ⚠️ **P0**
- [ ] **R9** `fetchMe()` 부트스트랩 위치 보완 — `(app)/layout.tsx`에 1회 호출 추가. 현재 `LandingRedirect.tsx`(루트만) → `/dashboard` 등 직접 진입 시 user null ⚠️
- [ ] **R10** 전역 에러 Toast — `Sonner` 설치 + `ApiException` → Toast 헬퍼. 현재 `disclosures/page.tsx` 인라인만 존재 ⚠️

---

## 영향 범위

- **영향 레이어**: frontend 전용. backend·DB·외부 계약 변경 없음.
- **DB 변경**: 없음

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `frontend/.env.local` | `NEXT_PUBLIC_API_URL` 값에 `/api/v1` 추가 |
| `frontend/.env.example` | 동일 |
| `frontend/src/app/(app)/layout.tsx` | `"use client"` 전환 + `useEffect(() => fetchMe(), [])` |
| `frontend/src/app/providers.tsx` | `Toaster` 마운트 (Sonner 설치 후) |
| `frontend/src/lib/api/client.ts` | (선택) `ApiException` 캐치 시 Toast 헬퍼 호출 or 각 페이지에서 onError |

---

## 관련 패턴 / 과거 사례

- `api_spec.md §1.2` — 401 TOKEN_EXPIRED → `POST /auth/refresh` → 재발급
- `api_spec.md §1.4` — 페이지네이션 envelope `{ content, page }` 구조
- BE Controller `@RequestMapping("/api/v1/disclosures")` — FE BASE_URL에 `/api/v1` 포함 필수

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| **URL prefix mismatch (R1)** | `.env.local` set 상태에서 `NEXT_PUBLIC_API_URL=http://localhost:8080` → 모든 API 호출 404. fallback(`http://localhost:8080/api/v1`)으로만 우연히 동작 중 |
| `(app)/layout.tsx` SSR 오염 (R9) | `fetchMe`는 `useEffect` 안에서만 호출. SSR에서 실행 시 `window` 없음 → `apiClient` warn 무시 필요. `"use client"` 필수 |
| Sonner 중복 Toaster (R10) | `providers.tsx`에 단 1개만 마운트. 기존 shadcn `useToast`와 혼재 금지 |
| 자본시장법 | 본 Spec은 데이터 표시 경로만 다룸. 분석 결과 생성 없음. 영향 없음 |

---

## 권장 구현 방향

- **R1**: `.env.local` · `.env.example` 한 줄 수정 → BE 기동 후 `/dashboard` 로딩으로 즉시 검증
- **R9**: `(app)/layout.tsx`를 `"use client"`로 전환 후 `useAuthStore`의 `fetchMe` useEffect 추가. `AppShell`·`AuthBroadcastListener`는 이미 client component이므로 layout client 전환 무해
- **R10**: `pnpm add sonner` → `providers.tsx`에 `<Toaster />` 추가 → `apiClient` 또는 개별 mutation onError에서 `toast.error(e.body.message)` 연결

---

## Tech Review (dc-tech-review · 2026-06-10)

> 2차 리뷰: R5·R6·R7 페이지별 Read 완료. 모든 R 상태 확정.

### 아키텍처 분해

- **영향 레이어**: frontend 전용 (backend·DB·외부 계약 변경 없음)
- **신규**: Sonner 패키지 설치 (R10)
- **수정**: `.env.local`·`.env.example` (R1), `(app)/layout.tsx` (R9), `providers.tsx` (R10)

### 현황 대조 (최종)

| R | 항목 | 판정 |
|---|------|------|
| R1 | `NEXT_PUBLIC_API_URL` prefix | ⚠️ 수정 필요 (P0) |
| R2 | 401 refresh 인터셉터 | ✅ 완료 |
| R3 | 대시보드 실데이터 | ✅ 완료 |
| R4 | 공시 피드 페이지네이션 | ✅ 완료 |
| R5 | 공시 상세 TierGate | ✅ 완료 — mock 없음, TierGate 분기 완전 구현 |
| R6 | 포트폴리오 mutation | ✅ 완료 — 422/409 에러 분기 포함 |
| R7 | 알림 설정 mutation | ✅ 완료 — grep "mock" hit는 KakaoPreview UI 미리보기 주석, 데이터 mock 아님 |
| R8 | 피드백 mutation | ✅ 완료 |
| R9 | fetchMe 부트스트랩 | ⚠️ 수정 필요 |
| R10 | 전역 Toast | ⚠️ 미도입 |

### 작업 카드 (확정)

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `.env.local` · `.env.example` URL `/api/v1` 추가 | config | 하 | - |
| 2 | `(app)/layout.tsx` `"use client"` + `fetchMe()` useEffect | frontend | 하 | - |
| 3 | Sonner 설치 + `providers.tsx` `<Toaster />` + mutation onError Toast 연결 | frontend | 중 | #1 |
| 4 | BE 기동 후 R1·R9·R10 통합 검증 (`/dashboard` 직접 진입, 401 시나리오) | test | 하 | #1~#3 |

### DB / 마이그레이션 영향

- 없음

### 외부 계약 영향

- 없음

### 리스크 & 법적 검토

| 리스크 | 대응 |
|------|------|
| R1 P0 — URL 404 | 수정 즉시 BE 기동 후 브라우저 `/dashboard` 로딩으로 검증 |
| R9 — layout SSR 오염 | `"use client"` 선언 필수. `useEffect` 내부 한정 |
| R10 — Toaster 중복 | `providers.tsx` 단 1개. 기존 `useToast` 혼재 금지 |

### 예상 wave 수

- **1 wave**: 카드 #1·#2·#3·#4 (모두 소규모 — 단일 wave 처리 가능)

### Approved 전환 권장 여부

- **Approved 전환 권장** — 잔여 작업 3항목 모두 범위 확정됨. `/dc-spec-move frontend-api-integration Approved` 후 `/dc-implement` 진입 가능.
