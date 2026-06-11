---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-11
---

# 소셜 OAuth 실연동 Spec (카카오·구글)

> 상태: Draft → **Approved** (2026-06-11, dc-tech-review 승인)

## 배경 / 목적

`/signup`, `/login` 페이지의 카카오·구글 소셜 버튼이 현재 `alert("백엔드 연동 후 활성화")`로만 구현되어 있다. 이 Spec은 백엔드 OAuth2 인프라(이미 `user-auth-jwt-oauth2` Done)와 프론트엔드를 실제로 연결한다.

- **현황**: 백엔드 `GET /auth/oauth/{provider}/url` + `POST /auth/oauth/{provider}/callback` 구현 완료. FE는 OAuth URL 조회 함수(`useOAuthUrl`)만 선언됨.
- **목표**: 카카오·구글 소셜 버튼 클릭 → OAuth 팝업/리다이렉트 → 콜백 처리 → JWT 발급 → 대시보드 이동.
- **제공자**: 카카오(1순위) + 구글. 네이버는 MVP 이후.

---

## 요구사항

- [ ] **R1** OAuth 리다이렉트 플로우 구현 — `GET /auth/oauth/{provider}/url` 응답 URL로 `window.location.href` 리다이렉트
- [ ] **R2** OAuth 콜백 라우트 생성 — `frontend/src/app/api/auth/callback/[provider]/route.ts` (Next.js Route Handler)
  - 쿼리 파라미터 `code`, `state` 수신
  - `POST /auth/oauth/{provider}/callback` 백엔드 호출
  - 성공: `authStore.setUser()` → `/dashboard` 리다이렉트
  - 실패: `/login?error=oauth_failed` 리다이렉트
- [ ] **R3** CSRF state 검증 — 백엔드가 state 발급·검증하므로 FE는 콜백 URL의 `state` 파라미터를 그대로 백엔드에 전달
- [ ] **R4** 카카오 버튼 실연동 — `/signup`·`/login`의 카카오 버튼 onClick 교체
- [ ] **R5** 구글 버튼 실연동 — 동일 패턴
- [ ] **R6** OAuth 에러 처리 — `/login?error=...` 쿼리 파라미터 감지 → 인라인 에러 메시지 표시
- [ ] **R7** 소셜 가입 온보딩 분기 — 소셜 신규 가입 시 약관 동의 미완료 여부 감지 → `/signup/terms`로 이동 (api_spec §2.1 참조)

---

## 영향 범위

- **영향 레이어**: frontend (신규 Route Handler + 기존 signup/login 수정)
- **DB 변경**: 없음 (백엔드 이미 처리)
- **외부 계약**: 카카오 개발자 콘솔 Redirect URI 등록 필요 (`/api/auth/callback/kakao`)

### 신규/수정 파일

| 파일 | 내용 |
|------|------|
| `frontend/src/app/api/auth/callback/[provider]/route.ts` | OAuth 콜백 Route Handler (신규) |
| `frontend/src/lib/api/auth.ts` | `initiateOAuth(provider)` 함수 추가 |
| `frontend/src/app/(auth)/signup/page.tsx` | 카카오·구글 버튼 onClick 교체 |
| `frontend/src/app/(auth)/login/page.tsx` | 동일 |

---

## 관련 패턴 / 과거 사례

- `user-auth-jwt-oauth2` (Done) — 백엔드 OAuth2 Kakao/Google/Naver Wave 4. `GET /auth/oauth/{provider}/url` → 인가 URL 발급, `POST /auth/oauth/{provider}/callback` → state CSRF 검증 + 자체 JWT 발급
- `api_spec.md §2.1` — OAuth 콜백 응답: 자체 JWT(`access_token`, `refresh_token`) + `Set-Cookie: dr_session`

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| 카카오 개발자 콘솔 Redirect URI 미등록 | 도메인 확정 후 등록 필수. 로컬 개발: `http://localhost:3000/api/auth/callback/kakao` |
| 소셜 가입 시 약관 동의 누락 | 백엔드에서 `consents` 미완료 사용자 403 반환 → FE에서 `/signup/terms`로 유도 |
| state 파라미터 없는 콜백 | CSRF 공격 가능성. 백엔드가 1차 검증, FE는 state 없으면 에러 처리 |

---

## 권장 구현 방향

```
// lib/api/auth.ts
export const initiateOAuth = async (provider: "kakao" | "google") => {
  const { url } = await apiClient<{ url: string }>(`/auth/oauth/${provider}/url`);
  window.location.href = url;  // 팝업 대신 전체 리다이렉트 — 카카오 정책상 팝업 불가
};
```

콜백 Route Handler:
```ts
// app/api/auth/callback/[provider]/route.ts
export async function GET(req, { params }) {
  const code = req.nextUrl.searchParams.get("code");
  const state = req.nextUrl.searchParams.get("state");
  const res = await fetch(`${process.env.API_URL}/auth/oauth/${params.provider}/callback`, 
    { method: "POST", body: JSON.stringify({ code, state }) });
  if (!res.ok) return NextResponse.redirect("/login?error=oauth_failed");
  return NextResponse.redirect("/dashboard");  // Set-Cookie는 백엔드가 처리
}
```

## Tech Review (dc-tech-review · 2026-06-11)

### 아키텍처 분해
- **영향 레이어**: frontend 전용 (BE `GET /auth/oauth/{provider}/url` + `POST /auth/oauth/{provider}/callback` 이미 Done)
- **신규**: `app/api/auth/callback/[provider]/route.ts` Route Handler
- **수정**: `lib/api/auth.ts` (`initiateOAuth` 함수 추가), `signup/page.tsx`, `login/page.tsx`

### 작업 카드
| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `initiateOAuth(provider)` 함수 추가 — `getOAuthUrl()` 호출 후 `window.location.href` 리다이렉트 | frontend/lib/api/auth.ts | 하 | - |
| 2 | OAuth 콜백 Route Handler — `code`+`state` 수신 → BE callback POST → `storeTokenCookies` → `/dashboard` or `/signup/terms` 리다이렉트 | frontend/app/api/auth/callback/[provider]/route.ts | 중 | #1 |
| 3 | `/signup` 카카오·구글 버튼 `alert` → `initiateOAuth` 교체 | frontend/signup/page.tsx | 하 | #1 |
| 4 | `/login` 동일 교체 + `?error=oauth_failed` 쿼리 인라인 에러 표시 | frontend/login/page.tsx | 하 | #1 |

### DB / 마이그레이션 영향
- 없음 (BE OAuth2 인프라 V1·V14 이미 존재)

### 외부 계약 영향
- 카카오 개발자 콘솔 Redirect URI 등록 필요: `http://localhost:3000/api/auth/callback/kakao` (로컬), 프로덕션 도메인도 동일 패턴
- 구글 OAuth2 클라이언트 Redirect URI 동일 패턴

### 구현 주의사항
- **콜백 핸들러 토큰 저장**: BE가 JSON body로 `access_token`/`refresh_token` 반환 → 기존 `storeTokenCookies` 패턴 재사용 (httpOnly 쿠키)
- **R7 온보딩 분기**: BE가 `consents` 미완료 시 403 반환 → `res.ok === false && res.status === 403` 감지 → `/signup/terms` 리다이렉트
- **state 없는 콜백 방어**: `code` 또는 `state` 없으면 즉시 `/login?error=oauth_failed`
- **`getOAuthUrl` 도메인 검증**: 이미 `ALLOWED_OAUTH_DOMAINS` 화이트리스트 구현됨 — `initiateOAuth`는 이를 재사용

### 리스크 & 법적 검토
- Open Redirect: `getOAuthUrl`의 `ALLOWED_OAUTH_DOMAINS` 검증이 이미 방어함 (보안 리뷰 통과 수준)
- 카카오 정책: 팝업 불가 → 전체 페이지 리다이렉트 방식만 허용 (Spec 권장 방향과 일치)
- CSRF: BE가 state 발급·검증 1차 담당, FE는 state 없는 콜백만 추가 방어

### 예상 wave 수
- **1 wave** — 전체 FE 변경, BE 의존성 없음, 소규모

