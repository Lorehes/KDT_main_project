---
type: spec
status: Draft
created: 2026-06-16
updated: 2026-06-16
---

# OAuth 동의 완료 강제화 및 UX 개선 Spec

> 상태: **Draft** (dc-plan 생성)

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
