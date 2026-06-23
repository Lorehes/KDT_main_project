---
type: spec
status: Approved
created: 2026-06-16
updated: 2026-06-23
---

# OAuth 동의 완료 강제화 및 UX 개선 Spec

> 상태: **Approved** (2026-06-23, dc-tech-review 승인)

## 배경 / 목적

코드 리뷰(2026-06-16) 결과 식별된 High-Security 이슈 E4 및 Medium 이슈 M-S1, M-M1 해소.

- **E4 (High)**: OAuth 신규 가입 후 토큰 쿠키가 저장된 상태에서 사용자가 URL을 직접 `/dashboard`로 변경하면 DISCLAIMER 미동의 상태로 서비스 전체 접근 가능. `middleware.ts`가 세션 쿠키 유무만 확인하고 동의 완료 여부를 체크하지 않음.
- **M-S1 (Medium)**: `?oauth=true` URL 파라미터 신뢰로 인한 가입 플로우 분기. URL 조작으로 이메일 계정 사용자가 의도치 않게 소셜 동의 API를 호출할 수 있음.
- **M-M1 (Medium)**: `terms/page.tsx` 한 파일에 이메일·소셜 두 플로우 혼합. 향후 플로우 분화 시 수정 폭발 위험.

전체 사용자(Free 티어 포함)에 해당. 자본시장법 §11.1 DISCLAIMER 동의는 법적 필수.

## 요구사항

- [ ] **R1** — FE `middleware.ts`: 보호 경로 진입 시 세션 쿠키는 있지만 consent 미완료 상태이면 `/signup/terms/oauth`로 강제 리다이렉트
- [ ] **R2** — consent 상태 판단: BE `GET /api/v1/users/me` 응답에 `consent_completed: boolean` 필드 추가 (TERMS·PRIVACY·DISCLAIMER 모두 agreed=true)
- [ ] **R3** — `?oauth=true` URL 파라미터 의존 제거: `route.ts`에서 `is_new_user=true` 시 redirect 목적지를 `/signup/terms/oauth`(별도 경로)로 변경
- [ ] **R4** — `OAuthTermsPage` 분리: `/signup/terms/oauth/page.tsx` 신규 생성 (소셜 전용 플로우). 공유 UI는 `TermsCheckboxList` 컴포넌트 추출
- [ ] **R5** — `/signup/terms/page.tsx` 정리: `isOAuth` 분기 제거, 이메일 전용으로 단순화

## 영향 범위 (조사 결과)

- **영향 레이어**: frontend(auth/signup), BE(user/dto)
- **영향 파일**:
  - `frontend/src/middleware.ts` — consent 완료 체크 로직 추가
  - `frontend/src/app/api/auth/callback/[provider]/route.ts` — redirect 목적지 `/signup/terms/oauth`로 변경
  - `frontend/src/app/(auth)/signup/terms/page.tsx` — isOAuth 분기 제거, 이메일 전용 단순화
  - `frontend/src/app/(auth)/signup/terms/oauth/page.tsx` — **신규** 소셜 전용 약관 동의 페이지
  - `frontend/src/components/signup/TermsCheckboxList.tsx` — **신규** 공유 UI 컴포넌트
  - `backend/.../user/dto/UserMeResponse.java` — `consentCompleted` 필드 추가
  - `backend/.../user/services/UserService.java` — `getMe()` 응답 조립 시 `hasRequiredConsents()` 호출
- **DB 변경**: 없음 (consent_logs 기존 구조 활용)
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- 기존 구현 참고: `frontend/src/middleware.ts` — 현재 `dr_session` 쿠키 유무로만 인증 체크
- 기존 `ConsentService.hasRequiredConsents()` — BE 동의 완료 판단 로직 (이번 PR에서 최적화 완료)
- `/signup` 라우트 구조: `(auth)/signup/{terms,verify,phone,profile,complete}` — 소셜 전용 `terms/oauth` 추가
- `PUBLIC_PATHS` 배열 현황: `["/", "/pricing", "/login", "/signup", "/dashboard/preview"]` — `/signup/terms/oauth`는 보호 경로이므로 추가 불필요 (토큰 있는 상태에서 접근)

## 리스크 / 법적 검토

- **자본시장법 §11.1**: DISCLAIMER 미동의 상태 서비스 접근 시 법적 책임 발생. middleware 강제화가 핵심 방어선.
- **성능**: middleware에서 매 요청마다 `GET /users/me` API 호출 시 레이턴시 증가. → 대안: JWT claims에 `consent_completed` 인코딩 (토큰 발급 시 BE가 포함) — 별도 API 불필요하나 consent 완료 즉시 토큰 갱신 필요.
- **UX**: 보호 경로 진입 시 consent 체크로 인한 추가 리다이렉트. 기존 로그인 사용자(이미 동의 완료)에게는 영향 없음.
- **엣지 케이스**: 이미 동의 완료한 사용자가 `/signup/terms/oauth` 직접 접근 시 — middleware에서 `consent_completed=true`이면 `/dashboard`로 리다이렉트.

## 권장 구현 방향

### 접근법 A: JWT claims에 `consent_completed` 인코딩 (권장)

- `AuthService.issueTokenPair()` 시 `hasRequiredConsents()` 결과를 JWT payload에 포함
- middleware에서 JWT 디코딩(서명 검증 없이 payload parse) 후 `consent_completed` 필드 확인
- **장점**: 추가 API 호출 없음, 레이턴시 0
- **단점**: consent 완료 즉시 반영하려면 새 토큰 발급 필요 (access token TTL 30분 동안 지연 가능)
- **완화**: `/me/oauth-consent` 성공 후 FE가 `/api/auth/refresh`로 토큰 즉시 갱신

### 접근법 B: 매 요청마다 `GET /users/me` 조회

- middleware에서 세션 쿠키로 `GET /api/v1/users/me` 호출, `consent_completed` 확인
- **장점**: 항상 최신 상태 반영
- **단점**: 모든 보호 경로 요청마다 BE API 추가 호출 → p95 레이턴시 50-100ms 증가

**결론**: 접근법 A 권장. consent 완료는 가입 직후 1회성 이벤트이므로 30분 지연은 허용 가능하고, `/me/oauth-consent` 성공 후 즉시 refresh로 완화.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-23)

### ⚠️ Spec 전제 정정 (구현 전 필독 — 실제 코드 확인 결과)

코드 직접 확인 결과 Spec의 핵심 전제 3건이 현재 코드와 어긋남. **이 정정을 반영하지 않으면 R1 강제화가 무력화됨.**

1. **[치명] `/signup/terms/oauth`는 보호 경로가 아니라 PUBLIC 경로가 된다.**
   Spec §영향범위 line 49는 "`/signup/terms/oauth`는 보호 경로이므로 PUBLIC_PATHS 추가 불필요"라 했으나 **반대**다.
   `middleware.ts`의 매칭은 `pathname === p || pathname.startsWith(p + "/")`이고 `PUBLIC_PATHS`에 이미 `/signup/terms`가 있으므로, `/signup/terms/oauth`는 `startsWith("/signup/terms/")`로 **PUBLIC 판정**된다. → consent 미완료 사용자를 이 경로로 보내도 미들웨어가 검사하지 않음.
   **해결**: PUBLIC_PATHS의 prefix 매칭 정책을 손봐야 함. 옵션 — (a) `/signup/terms`를 정확 매칭(`===`) 전용으로 바꾸고 하위 경로는 개별 열거, (b) consent 체크를 PUBLIC 판정보다 **먼저** 수행. (b) 권장(보호 로직이 PUBLIC 단락보다 우선).

2. **[중대] returning-user 판정은 이미 `consent_completed`가 아니라 `onboarding_completed_at`(V20)으로 진화했다.**
   `AuthService.java` 주석(line 47–49)·`oauthCallback()`은 `hasRequiredConsents()` 기반 판정을 **버그로 규정하고 폐기**, `onboarding_completed_at IS NULL` 여부로 is_new_user를 반환한다(약관만이 아니라 phone/profile/complete까지 도달해야 returning).
   → Spec R2의 `consent_completed`(TERMS·PRIVACY·DISCLAIMER 3종)는 **onboarding 완료보다 좁은 게이트**다. 약관만 동의하고 phone/profile 미완료인 사용자는 `consent_completed=true`지만 `/dashboard` 접근은 막아야 한다.
   **결정 필요**(아래 §결정 필요 D1): middleware가 enforce할 기준을 `consent_completed`로 둘지 `onboarding_completed`로 둘지. E4의 법적 핵심은 DISCLAIMER지만, 실제 가드는 onboarding이 상위집합. **권장: JWT에 `onboarding_completed` 인코딩**으로 통일하고 E4(법적 DISCLAIMER)는 그 부분집합으로 자연 충족.

3. **[경미] `UserMeResponse.from(UserEntity)`는 엔티티만 받는다.**
   R2의 `consent_completed`를 채우려면 `getMe()`에서 `ConsentService.hasRequiredConsents()` 결과를 주입해야 하나, 현재 `UserService`는 `ConsentService` 의존이 없고 `from()`은 1-인자 팩토리다. → `from(user, consentCompleted)` 오버로드 + `UserService`에 `ConsentService` 주입 필요(같은 `user/` 도메인 내부라 의존 허용).
   단, 접근법 A(JWT) 채택 시 middleware는 `/users/me`를 호출하지 않으므로 R2는 **FE 표시용으로만** 필요(우선순위 하향 가능).

### 아키텍처 분해
- **영향 레이어**: frontend(middleware, auth/signup, components), backend(user — JwtTokenProvider/AuthService/UserService/DTO)
- **신규**: `frontend/src/app/(auth)/signup/terms/oauth/page.tsx`, `frontend/src/components/signup/TermsCheckboxList.tsx`
- **수정**: `frontend/src/middleware.ts`, `.../callback/[provider]/route.ts`, `.../signup/terms/page.tsx`, `backend JwtTokenProvider.generateAccessToken()`, `AuthService.issueTokenPairInternal()`, `UserMeResponse`, `UserService.getMe()`
- **채택 권장 접근법**: 접근법 A(JWT claim) — middleware Edge 런타임에서 매 요청 BE 호출(접근법 B)은 p95 +50~100ms이고 서명검증도 어려움. A는 payload base64 디코드만(라우팅 용도, 인가는 BE가 재검증)이라 적합.

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `JwtTokenProvider.generateAccessToken()`에 `onboarding_completed`(bool) claim 추가 — 시그니처 확장(유일 호출처 `issueTokenPairInternal`) | backend/user(security) | BE | 중 | - |
| 2 | `AuthService.issueTokenPairInternal()`: `user.getOnboardingCompletedAt()!=null` 전달 | backend/user | BE | 하 | #1 |
| 3 | `middleware.ts`: 보호 경로 진입 시 `dr_session` JWT payload 디코드 → `onboarding_completed=false`면 `/signup/terms/oauth` 리다이렉트. **PUBLIC prefix 정책 수정(전제정정 #1)** 동반 | frontend/middleware | FE | 중 | #1 |
| 4 | `route.ts` callback: `is_new_user=true` redirect 목적지 `/signup/terms?oauth=true` → `/signup/terms/oauth` | frontend/auth | FE | 하 | #5 |
| 5 | `TermsCheckboxList` 공유 컴포넌트 추출(`TERMS_ITEMS`+체크박스 UI+전체동의) | frontend/components | FE | 중 | - |
| 6 | `/signup/terms/oauth/page.tsx` 신규 — 소셜 전용(oauth-consent 호출), `TermsCheckboxList` 사용 | frontend/auth | FE | 중 | #5 |
| 7 | `/signup/terms/page.tsx`: `isOAuth`·`?oauth=true` 분기 제거, 이메일 전용 단순화, `TermsCheckboxList` 사용 | frontend/auth | FE | 중 | #5 |
| 8 | `oauth-consent` 성공 후 FE가 `/api/auth/refresh`로 토큰 즉시 갱신(접근법 A의 30분 지연 완화) | frontend/auth | FE | 하 | #1,#6 |
| 9 | (선택, FE 표시용) `UserMeResponse.consent_completed` + `UserService`에 `ConsentService` 주입 | backend/user | BE | 하 | - |

### DB / 마이그레이션 영향
- **없음.** 이 Spec은 `onboarding_completed_at`(V20 이미 존재)·`consent_logs`(기존)만 읽는다. 신규 Flyway 불필요.
- 참고: 현재 최신 마이그레이션은 **V20**. (자매 Spec `oauth-consent-data-integrity`가 "차기 V19"라 적은 건 stale — 실제 V19/V20 소진됨. 그 Spec 구현 시 V21부터 사용해야 함.)

### 외부 계약 영향
- DART/KRX/카카오/LLM: 없음.
- 내부 계약 변경: `GET /users/me` 응답에 `consent_completed` 추가(카드 #9, 가산적·비파괴적). AuthResponse·JWT claim은 내부 토큰 포맷 — FE middleware가 디코드하므로 claim 이름(`onboarding_completed`) FE/BE 합의 필요.

### 리스크 & 법적 검토
- **자본시장법 §11.1 (E4 핵심)**: DISCLAIMER 미동의 서비스 접근 차단이 목적. 접근법 A + `onboarding_completed` 게이트면 DISCLAIMER는 부분집합으로 자연 충족(전제정정 #2). 단 JWT claim은 발급 시점 스냅샷 → 동의 직후 access token(30분 TTL) 안에서는 stale. **카드 #8(즉시 refresh)이 법적 방어선의 필수 보강** — 누락 시 동의 완료 후에도 최대 30분 차단 지속(UX 저하)이거나, 반대로 미완료자가 기존 토큰으로 통과하는 창은 없음(안전 측 실패).
- **middleware 미검증 JWT 디코드**: 서명 검증 없이 payload만 파싱 → 위조 토큰으로 게이트 통과 가능하나, **모든 보호 API는 BE가 서명 재검증**하므로 데이터 노출 없음(라우팅 편의일 뿐). 허용 가능. 단, 만료/변조 토큰이면 BE 401 → 기존 이중 리다이렉트(/login)로 수렴.
- **두 Spec 동시 작업 충돌**: 본 Spec(접근법 A)과 자매 `oauth-consent-data-integrity`가 모두 `AuthService`를 수정. 카드 #2(issueTokenPairInternal)와 data-integrity의 `autoSignup()`/캐시 변경이 겹칠 수 있음 → **구현 순서·브랜치 분리 필요**.
- **엣지 케이스**: onboarding 완료자가 `/signup/terms/oauth` 직접 접근 → middleware에서 `onboarding_completed=true`면 `/dashboard`로 리다이렉트(카드 #3에 포함).

### 결정 (확정 2026-06-23)
- **D1 (차단 기준)**: **`onboarding_completed`** 채택. `onboarding_completed_at IS NOT NULL`(V20)이 실제 앱 게이트와 일치하며, DISCLAIMER(E4 법적 요건)는 부분집합으로 자연 충족. JWT claim 이름: `onboarding_completed` (bool). 카드 #1·#2·#3이 이 기준으로 구현됨.
- **D2 (리다이렉트 목적지)**: **단일 `/signup/terms/oauth`** (MVP). 약관 재노출은 허용 — 이미 동의된 경우 oauth-consent API가 덮어씌워도 무해하며, resume 지점 정밀화는 차기 이슈. 카드 #4·#6이 이 경로를 사용.

### 예상 wave 수
- **Wave 1 (BE, 카드 #1·#2)**: JWT claim 추가 — FE 게이트의 선행 조건.
- **Wave 2 (FE 강제화 + 컴포넌트, 카드 #3·#5·#6·#7·#4·#8)**: middleware 게이트 + 페이지 분리 + 즉시 refresh. (#5 먼저, 이후 병렬)
- **Wave 3 (선택, 카드 #9)**: `consent_completed` 표시용 — 우선순위 하. 별도 PR 또는 생략 가능.
- 총 **2 wave(+1 선택)** 권장.
