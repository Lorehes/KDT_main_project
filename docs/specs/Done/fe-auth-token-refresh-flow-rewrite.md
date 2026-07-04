---
type: spec
status: Done
created: 2026-06-09
updated: 2026-06-10
---

# FE 토큰 갱신 플로우 재설계 Spec

> 상태: **Done** (2026-06-10, R4~R10 전 요구사항 구현 + Playwright E2E 검증 완료)

## 배경 / 목적

`frontend-api-integration`(Draft) 초안에서 401 인터셉터가 단순 boolean 플래그로 구현됐고, 토큰을 JS 메모리로 전달하는 구조가 남아 있다. 5축 리뷰에서 **race condition + 토큰 평문 노출 + SSR 컨텍스트 실패** 의 P0 3건이 확인됐다. 본 Spec 은 토큰 갱신 흐름 전체를 Promise 큐 + httpOnly 쿠키 기반으로 재설계한다.

- **현황**: `lib/api/client.ts` 의 `let isRefreshing = false` 모듈 변수, `refresh` 상대경로 호출, `auth.ts:43` JSON body 로 토큰 전달
- **목표**: (1) 동시 401 무손실 처리, (2) refresh_token JS 미노출, (3) SSR/RSC 컨텍스트에서도 동작
- **BM 연관**: 전 사용자 — 토큰 만료 시 데이터 손실/강제 로그아웃 방지

---

## 요구사항

### 토큰 저장 모델

- [ ] **R1** BE `/auth/login` · `/auth/signup` · `/auth/refresh` 응답에서 직접 `Set-Cookie: access_token; HttpOnly; Secure; SameSite=Strict; Path=/` 발급 — JSON body 의 access_token 제거 또는 백워드 호환을 위해 한 마이너 버전 동안 동시 발급
- [ ] **R2** `frontend/src/lib/api/auth.ts` `storeTokenCookies()` 제거 — BE 가 직접 Set-Cookie 하므로 FE 가 토큰을 JS 에서 다루지 않음
- [ ] **R3** Next.js Route Handler `/api/auth/session`, `/api/auth/refresh`, `/api/auth/logout` 은 서버 컴포넌트 전용으로 격리 — 클라이언트 번들에서 토큰 흐름 코드 제거

### Refresh 인터셉터 재설계

- [ ] **R4** `lib/api/client.ts` `isRefreshing` boolean → `refreshPromise: Promise<void> | null` 패턴으로 교체. 동시 401 요청들이 동일 Promise 를 await 후 재시도
- [ ] **R5** refresh 호출 경로를 절대경로(BASE_URL 기반)로 통일 — SSR/RSC 컨텍스트에서 상대경로 fetch 실패 방지. `AUTH_BASE_URL` 환경변수 분리 검토
- [ ] **R6** refresh fetch 에 `credentials: "include"` 명시 — httpOnly 쿠키가 cross-origin 시에도 전송되도록 강제
- [ ] **R7** refresh 실패 시 fallback — 401 재발 시 `authStore.logout()` 호출 후 `LOGIN_PATH` 상수 로 redirect. 무한 401 루프 방지(재시도 1회 제한)

### 다중 탭 / SSR 컨텍스트

- [ ] **R8** `BroadcastChannel('auth')` 도입 — 한 탭에서 refresh 성공 시 다른 탭에 갱신 알림. RTR 도입 시 토큰 경쟁 차단
- [ ] **R9** Server Components / Server Actions 에서 호출되는 fetch 와 client fetch 분리 — `lib/api/client.ts` 는 클라이언트 전용, 서버 fetch 는 `lib/api/server-client.ts` 신규 (Next.js cookies() 사용)

### 검증 · 테스트

- [ ] **R10** Playwright 통합 테스트 — (a) 동시 401 5건 → refresh 1회만 호출됨 검증, (b) refresh 실패 시 /login redirect, (c) 다중 탭 BroadcastChannel 동기화

---

## 영향 범위

- **영향 레이어**: backend (`user/controllers/AuthController` 의 Set-Cookie 헤더) + frontend (`lib/api`, Route Handlers, 모든 인증 호출 페이지)
- **DB 변경**: 없음
- **외부 계약**: 백워드 호환 종료 시 모바일 앱(향후)에서 JSON body 토큰 의존 코드 일괄 교체 필요

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../user/controllers/AuthController.java` | R1 Set-Cookie 발급 |
| `backend/.../user/services/AuthService.java` | R1 토큰 헤더 빌더 |
| `frontend/src/lib/api/client.ts` | R4 Promise 큐 + R5 절대경로 + R6 credentials + R7 retry 제한 |
| `frontend/src/lib/api/auth.ts` | R2 storeTokenCookies 제거 |
| `frontend/src/app/api/auth/session/route.ts` | R3 서버 격리 |
| `frontend/src/app/api/auth/refresh/route.ts` | R3 서버 격리 |
| `frontend/src/app/api/auth/logout/route.ts` | R3 서버 격리 |
| `frontend/src/lib/api/server-client.ts` (신규) | R9 서버 fetch |
| `frontend/src/lib/constants.ts` | `LOGIN_PATH`, `AUTH_BASE_URL` 상수 |
| `frontend/src/lib/auth/broadcast.ts` (신규) | R8 BroadcastChannel 래퍼 |

---

## 관련 패턴 / 과거 사례

- `user-auth-jwt-oauth2` (Done) — JWT 발급·refresh 토큰 rotation 로직 (BE)
- `frontend-api-integration` (Draft) — 401 인터셉터 R2 요구사항 — 본 Spec 이 그 구현 단계
- `frontend-oauth-social` (Draft) — OAuth 콜백 Route Handler 와 동일한 server-only 격리 패턴

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| BE Set-Cookie 변경으로 기존 클라이언트(현 FE) 일시 호환 깨짐 | 한 마이너 버전 동안 JSON body + Set-Cookie 동시 발급. CHANGELOG 명시 |
| httpOnly 쿠키 SameSite=Strict 가 OAuth 리다이렉트 흐름에서 누락 | OAuth 콜백 Route Handler 에서 도메인 자체 redirect 후 쿠키 설정 — same-site 처리. 필요 시 SameSite=Lax 폴백 |
| BroadcastChannel iOS Safari 구버전 미지원 | 폴백: `localStorage` event 리스너 — 동일 origin 탭 간 동기화 가능 |
| Promise 큐 메모리 누수 (refresh 실패 시 Promise 가 resolve 안 됨) | `try/finally` 로 `refreshPromise = null` 보장 + timeout(5초) 후 강제 reject |
| SSR 환경에서 cookies() 미접근 시 데이터 페치 실패 | server-client 명시적 분리 + Server Component 가이드 문서화 |

---

## 권장 구현 방향

- Wave 1 (BE Set-Cookie 도입) → Wave 2 (FE Promise 큐 + 절대경로) → Wave 3 (BroadcastChannel + SSR 분리) → Wave 4 (Playwright 회귀)
- [[security-hardening-mvp]] 의 CSP `connect-src` 와 정합 — refresh 호출 도메인이 CSP 에 포함되도록 동시 갱신
- `authStore.logout()` 의 `window.location.href = "/login"` 3곳 분산은 [[architecture-refactoring-cleanup]] 에서 `LOGIN_PATH` 상수화로 통합

## Tech Review (dc-tech-review · 2026-06-10)

### 아키텍처 분해

- **영향 레이어**: frontend (`lib/api/client.ts`, `lib/stores/authStore.ts`, `lib/api/auth.ts`, `app/api/auth/**`, 신규 `lib/constants.ts`, `lib/auth/broadcast.ts`, `lib/api/server-client.ts`) + 없음(BE 변경 없음)
- **R1·R2 스킵 결정**: `session/route.ts` 브리지 패턴이 이미 httpOnly 쿠키를 올바르게 저장(`dr_session`, `dr_refresh`). BE 가 Set-Cookie 를 직접 발급하지 않아도 보안상 동등. `storeTokenCookies()` 유지 — BE 대규모 변경 없이 R1·R2 불필요. 후속 BE 업데이트 시 제거 가능.
- **이미 완료**: Route Handlers(`refresh`·`logout`·`session`)는 구현 정상. `doFetch credentials:"include"` 기완료(R6).
- **신규 파일**: `lib/constants.ts`, `lib/auth/broadcast.ts`, `lib/api/server-client.ts`
- **수정 파일**: `lib/api/client.ts`, `lib/stores/authStore.ts`

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `lib/constants.ts` 신규 — `LOGIN_PATH = "/login"`, `REFRESH_PATH = "/api/auth/refresh"`, `LOGOUT_PATH = "/api/auth/logout"`. 환경변수 `NEXT_PUBLIC_SITE_URL`로 절대 refresh URL 구성(`${NEXT_PUBLIC_SITE_URL}/api/auth/refresh`). SSR 환경에서 절대경로 없으면 `http://localhost:3000` 폴백 | frontend/lib | FE | 하 | - |
| 2 | `lib/api/client.ts` Promise 큐 재설계 — `isRefreshing: boolean` → `refreshPromise: Promise<void> \| null`. `performRefresh()` 함수 분리: 진행 중이면 기존 Promise 반환, 아니면 신규 생성 후 `finally { refreshPromise = null }` 보장. timeout 5초 후 강제 reject 추가(Promise 메모리 누수 방지). R5: `/api/auth/refresh` → `REFRESH_PATH` 절대경로 상수 사용. R7: 401 루프 방지 — `refreshPromise` 존재 중에 재시도 한 번으로 제한 (R4·R5·R7) | frontend/lib | FE | 상 | #1 |
| 3 | `lib/stores/authStore.ts` + `lib/api/auth.ts` — `window.location.href = "/login"` 3곳을 `LOGIN_PATH` 상수 import로 교체. `authStore.logout()`에서 BroadcastChannel 알림 발행 호출(R7 상수화, R8 연동 준비) | frontend/lib | FE | 하 | #1 |
| 4 | `lib/auth/broadcast.ts` 신규 — `BroadcastChannel('dr_auth')` 래퍼. `postMessage('logout')` / `postMessage('refresh')` 발행. 수신 측: `'logout'` → `authStore.logout()` 재호출 없이 `setUser(null)` + redirect. iOS Safari 폴백: `localStorage` + `storage` event (R8) | frontend/lib | FE | 중 | #3 |
| 5 | `lib/api/server-client.ts` 신규 — Server Components·Server Actions 전용 fetch. `import { cookies } from "next/headers"` 로 `dr_session` 쿠키 직접 읽어 `Authorization: Bearer {token}` 헤더 세팅. 401 시 throw(SSR에서 redirect는 호출자 책임). 클라이언트 번들 격리를 위해 `"server-only"` 패키지 import (R9) | frontend/lib | FE | 중 | #1 |
| 6 | Playwright 통합 테스트 — (a) 동시 401 5건 → `/api/auth/refresh` 호출 1회만 확인, (b) refresh 실패 시 `/login` redirect, (c) BroadcastChannel: 탭 A logout → 탭 B 자동 redirect (R10) | frontend/test | FE | 중 | #2·#4 |

### DB / 마이그레이션 영향

- 없음 — BE 변경 없음, DB 스키마 변경 없음.

### 외부 계약 영향

- **BE**: R1 스킵으로 `AuthController` 변경 없음. `refresh_token` JSON body 유지.
- **CSP**: `security-hardening-mvp` 에서 `connect-src 'self' ${NEXT_PUBLIC_API_URL} wss:` 설정됨. Route Handler `/api/auth/**` 는 same-origin 이므로 CSP 변경 불필요.
- **DART/KRX/카카오**: 변경 없음.

### 리스크 & 법적 검토

| 리스크 | 대응 |
|--------|------|
| Promise 큐 메모리 누수 — refresh 실패 시 큐 요청들이 resolve 안 됨 | `try/finally { refreshPromise = null }` + 5초 timeout reject 필수 |
| BroadcastChannel iOS Safari 구버전 미지원 | `typeof BroadcastChannel !== "undefined"` 가드 + `localStorage` + `storage` event 폴백 |
| SSR `server-client.ts` 에서 cookies() 실패 — 서버 컴포넌트에서 쿠키 없을 경우 | 빈 토큰이면 Unauthorized throw — 레이아웃 수준에서 redirect 처리 |
| `NEXT_PUBLIC_SITE_URL` 미설정 시 SSR refresh 경로 오류 | `process.env.NEXT_PUBLIC_SITE_URL ?? "http://localhost:3000"` 폴백 + 배포 가이드 명시 |
| Wave 1 완료 후 Wave 2 이전 기간 동안 다중 탭 간 동기화 없음 | MVP 허용 범위 — 탭 A 에서 로그아웃해도 탭 B 에서 API 계속 동작(쿠키 삭제 전까지). Wave 2 BroadcastChannel로 해소 |

### 예상 wave 수

- **Wave 1 (핵심 버그)**: #1·#2·#3 — Promise 큐 + 절대경로 + 상수화. 이것만으로 P0 race condition 해소.
- **Wave 2 (멀티탭)**: #4 — BroadcastChannel + localStorage 폴백.
- **Wave 3 (SSR + 테스트)**: #5·#6 — server-client + Playwright 회귀.
- Wave 1 완료 후 즉시 `dc-push` 권장 (P0 해소 목적). Wave 2·3은 후속.
