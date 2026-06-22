---
type: spec
status: Draft
created: 2026-06-22
updated: 2026-06-22
---

# dc-review-frontend 인증 세션 캡처 Spec

> 상태: **Draft** (dc-plan 생성)
> 발견: `pricing-nav-auth-consistency` dc-review-frontend Info 이슈
> 관련: [[pricing-nav-auth-consistency]], [[review-frontend-hover-capture]]

## 배경 / 목적

`dc-review-frontend` Playwright 캡처 스크립트는 현재 비로그인 상태만 지원한다.
`pricing-nav-auth-consistency` Spec의 검증 경로 중:

| 경로 | 현재 상태 |
|------|-----------|
| ① 비로그인 `/pricing` | ✅ 자동 캡처 가능 |
| ② 로그인 사이드바 클라 nav → `/pricing` | ❌ 코드 리뷰 논리 확인만 |
| ③ 로그인 `/pricing` 하드리프레시 | ❌ 코드 리뷰 논리 확인만 |

경로 ②③에서 `PublicNavbar`가 **"대시보드로 →"** CTA를 실제로 렌더링하는지 시각적으로 자동 검증할 수 없는 상태다.

`dr_session` 쿠키를 Playwright context에 주입하면 서버 컴포넌트의 presence 판정을 트리거할 수 있다(`(public)/layout.tsx:15` `Boolean(cookies().get("dr_session"))`). 이를 통해 인증 사용자가 보는 네비바 CTA를 스크린샷으로 확인할 수 있다.

- **적용 대상**: `dc-review-frontend` 스킬 전체 (인증 필요 페이지 캡처 공통)
- **BM 관련 없음**: 개발 도구 개선

## 요구사항

- [ ] `review-capture.js`에 `--auth <mode>` CLI 옵션 추가 (default: 비로그인)
- [ ] `--auth sentinel` 모드: `dr_session=playwright-test-sentinel` 쿠키 주입 — presence-only 판정 트리거 (BE 불필요, `/pricing` 네비바 CTA 검증 충분)
- [ ] `--auth login:<email>:<password>` 모드: 실 로그인 — `POST http://localhost:3000/api/auth/session` 호출 후 실제 JWT 주입 (BE 실행 필요, `(app)` 페이지 전체 캡처 가능)
- [ ] `--auth state:<path>` 모드: 저장된 `storageState.json` 재사용 — login 모드로 한 번 인증 후 파일 저장
- [ ] 인증 컨텍스트 생성 시 PC/Mobile 양쪽 모두 동일 쿠키 적용
- [ ] 리포트 JSON에 `authMode` 필드 추가 (`"none" | "sentinel" | "login" | "state"`)
- [ ] SKILL.md Phase 1 섹션에 `--auth` 옵션 사용법 문서화
- [ ] `--auth login` 모드에서 자격증명을 `.env.review` 파일에서 로드하는 옵션 지원 (`REVIEW_AUTH_EMAIL`, `REVIEW_AUTH_PASSWORD`) — CLI 인자 직접 전달 시 보안 주의 명시

## 영향 범위 (조사 결과)

- 영향 레이어: **skill 스크립트 only** (FE 코드·BE·DB 무관)
- 영향 파일:
  - `.claude/skills/dc-review-frontend/scripts/review-capture.js` — **핵심 수정 대상**
  - `.claude/skills/dc-review-frontend/SKILL.md` — `--auth` 옵션 사용법 추가
- DB 변경: **없음**
- 외부 계약: **없음**

## 관련 패턴 / 과거 사례

- `dr_session` 쿠키 생성 참고: `frontend/src/app/api/auth/session/route.ts` — `httpOnly:true, sameSite:"lax", path:"/"`
- `middleware.ts:28`: `request.cookies.get("dr_session")` presence-only 판정 — sentinel 쿠키로 통과
- `(public)/layout.tsx:15`: `Boolean((await cookies()).get("dr_session"))` — sentinel로 `isAuthenticated=true` 반환
- Playwright httpOnly 쿠키 주입: `newContext({ storageState: { cookies: [{httpOnly:true, ...}] } })`
- 과거 해결 사례: `docs/solutions` 미존재 — 해당 없음

## 리스크 / 법적 검토

- **자본시장법/개인정보**: 해당 없음
- **보안**: `--auth login:email:password` CLI 직접 전달은 shell history에 자격증명 노출 위험 → `.env.review` 파일 로드 권장, git에 커밋 금지(`.gitignore` 추가)
- **sentinel 모드 한계**: `useAuthStore` 미hydration → `PricingClient`의 `currentPlanName` null(티어 없음). 네비바 CTA 검증에는 충분, 플랜 카드 "현재 플랜" 표시 검증은 login 모드 필요

## 권장 구현 방향

### sentinel 모드 핵심 코드

```js
function createAuthContext(browser, viewportOpts, authMode) {
  const cookieBase = {
    domain: 'localhost',
    path: '/',
    httpOnly: true,
    secure: false,
    sameSite: 'Lax',
  };

  if (authMode === 'sentinel') {
    return browser.newContext({
      ...viewportOpts,
      storageState: {
        cookies: [{ name: 'dr_session', value: 'playwright-test-sentinel', ...cookieBase }],
        origins: [],
      },
    });
  }
  // 비로그인 기본
  return browser.newContext(viewportOpts);
}
```

### login 모드 핵심 코드

```js
async function loginAndGetState(baseUrl, email, password) {
  // 임시 컨텍스트로 실 로그인
  const browser = await chromium.launch({ headless: true });
  const ctx = await browser.newContext();
  const page = await ctx.newPage();

  // BE 로그인 직접 호출
  const res = await page.request.post(`${baseUrl}/api/proxy/auth/login`, {
    data: { email, password },
  });
  const { access_token, refresh_token } = await res.json();

  // /api/auth/session Route Handler로 httpOnly 쿠키 세팅
  await page.request.post(`${baseUrl}/api/auth/session`, {
    data: { access_token, refresh_token },
  });

  const state = await ctx.storageState();
  await browser.close();
  return state; // cookies 포함
}
```

### CLI 사용 예시 (SKILL.md 추가)

```bash
# 비로그인 (현재 기본값)
node review-capture.js http://localhost:3000/pricing

# 인증 네비바 presence 검증 (BE 불필요)
node review-capture.js http://localhost:3000/pricing --auth sentinel

# 실 로그인 전체 캡처 (BE 실행 필요)
node review-capture.js http://localhost:3000/dashboard --auth login:test@example.com:password

# 저장된 세션 재사용
node review-capture.js http://localhost:3000/dashboard --auth state:/tmp/auth-state.json
```

## Tech Review (dc-tech-review · 2026-06-22)

### 아키텍처 분해
- 영향 레이어: **skill 스크립트 only** — `.claude/skills/dc-review-frontend/` 하위 2파일. FE 코드·BE·DB 무관.
- 신규: `createAuthContext()` / `loginAndGetState()` 헬퍼 함수 (review-capture.js 내부)
- 수정: `reviewPage()` 컨텍스트 생성 분기, JSON 리포트 구조(`authMode` 필드), SKILL.md Phase 1

### 핵심 버그 보정 (구현 시 반드시 적용)

**① `loginAndGetState()` API 경로 오류**
Spec 권장 코드의 `/api/proxy/auth/login`은 존재하지 않는 FE 경로.
`apiClient`는 `NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1"`로 BE를 직접 호출(FE 프록시 레이어 없음).

```js
// ❌ 잘못된 경로 (Spec 초안)
page.request.post(`${baseUrl}/api/proxy/auth/login`, ...)

// ✅ 올바른 BE 직접 호출
const beUrl = process.env.REVIEW_BE_URL ?? 'http://localhost:8080/api/v1';
page.request.post(`${beUrl}/auth/login`, {
  data: { email, password },
  headers: { 'Content-Type': 'application/json' },
})
```

**② browser 중복 생성 — 파라미터 주입으로 교체**
`loginAndGetState()` 내부 `chromium.launch()` 제거 → outer `browser` 파라미터 수신:

```js
async function loginAndGetState(browser, baseUrl, beUrl, email, password) {
  const ctx = await browser.newContext();
  const req = ctx.request;
  const loginRes = await req.post(`${beUrl}/auth/login`, { ... });
  const { access_token, refresh_token } = await loginRes.json();
  await req.post(`${baseUrl}/api/auth/session`, { data: { access_token, refresh_token } });
  const state = await ctx.storageState();
  await ctx.close();
  return state;
}
```

**③ `.env.review` dotenv 로딩 — 경량 직접 파싱**
`dotenv`는 `/tmp` playwright 의존성에 미포함 → readline 없이 파일 직접 파싱:

```js
function loadEnvFile(path) {
  if (!fs.existsSync(path)) return {};
  return Object.fromEntries(
    fs.readFileSync(path, 'utf8').split('\n')
      .filter(l => l.includes('=') && !l.startsWith('#'))
      .map(l => l.split('=').map(s => s.trim()))
  );
}
```

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | CLI 인자 파서 + `createAuthContext()` — `--auth none\|sentinel\|login:…\|state:…` 파싱, sentinel 쿠키 주입, PC/Mobile 모두 적용 | skill/scripts | FE | 하 | - |
| 2 | `loginAndGetState()` — BE 직접 POST(`REVIEW_BE_URL`), `/api/auth/session` 쿠키 등록, storageState 반환. 버그 보정 ①② 적용 | skill/scripts | FE | 중 | #1 |
| 3 | `--auth state:<path>` 재사용 모드 — `newContext({ storageState: path })` 직접 적용, 파일 존재 여부 guard | skill/scripts | FE | 하 | #1 |
| 4 | `.env.review` 경량 파싱 + `REVIEW_AUTH_EMAIL/PASSWORD/BE_URL` 환경변수 지원, `.gitignore`에 `.env.review` 추가 + `.env.review.example` 템플릿 생성, 리포트 JSON `authMode` 필드 추가 | skill/scripts | FE | 하 | #2 |
| 5 | SKILL.md `--auth` 옵션 사용법 3가지(sentinel/login/state) 예시 + 보안 주의사항 문서화 | skill | FE | 하 | #4 |

### DB / 마이그레이션 영향
- **없음.** Flyway 마이그레이션 불필요. 스키마 무변경.

### 외부 계약 영향
- **없음.** DART/KRX/카카오/LLM 무관.
- login 모드는 BE `/auth/login` 직접 호출 — BE가 실행 중이어야 하고 테스트 계정 존재 필요. API 스펙 변경 시 `loginAndGetState()` 함께 수정.

### 리스크 & 법적 검토
- **자본시장법/개인정보**: 해당 없음.
- **보안 — shell history 노출**: `--auth login:email:password` CLI 직접 전달은 `ps aux`·shell history에 평문 자격증명 노출. `.env.review` 파일 방식 강력 권장. `.env.review`를 `.gitignore`에 반드시 추가(카드 #4).
- **sentinel 모드 한계**: `useAuthStore` 미hydration → `PricingClient.currentPlanName` null, `(app)` API 호출 401. 네비바 CTA 검증 용도(Spec 목적)에는 충분. 전체 인증 페이지 검증은 login/state 모드 필요.
- **BE 의존성 (login 모드)**: BE 미실행 시 `loginAndGetState()` 실패 → 스크립트 전체 중단. BE 연결 실패 시 sentinel fallback 또는 명확한 에러 메시지 출력 처리 필요.

### 예상 wave 수
- **1 wave** (단일 소규모 스크립트 PR, 스킬 파일 2개 + `.gitignore` 1줄 수정)
