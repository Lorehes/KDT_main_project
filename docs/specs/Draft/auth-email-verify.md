---
type: spec
status: Draft
created: 2026-06-10
updated: 2026-06-10
---

# 이메일 인증 OTP Spec (auth-email-verify)

> 상태: **Draft** — [[mvp-missing-endpoints]] C1 분리 신규 생성 (2026-06-10)

## 배경 / 목적

`frontend-full-ui-implementation`(Done)에서 `signup/verify/page.tsx` 의 이메일 OTP 화면은 UI만 구현됐으나 백엔드 엔드포인트가 미존재 — `TODO: POST /auth/email/verify` 주석 2건이 남아 있다. 가입 플로우가 OTP 검증 없이 `/signup/terms`로 직행해 이메일 소유 확인이 불가한 상태.

- **현황**: `signup/verify/page.tsx:41,49` TODO 2건. 백엔드 이메일 OTP 엔드포인트 미구현. `api_spec.md` 미명시.
- **목표**: 이메일 OTP 발송·검증 엔드포인트 구현 + FE 연동. 타이머 만료 시 재전송 흐름 완성.
- **플로우 위치**: `/signup` (이메일 입력) → **`/signup/verify` (OTP 검증, 본 Spec)** → `/signup/terms` → `/signup/phone` → `/signup/profile`
- **BM 연관**: Free 가입 신뢰성 — 이메일 소유 확인으로 허위 가입 방지.

---

## 요구사항

### Backend

- [ ] **R1** `POST /api/v1/auth/email/send-otp` — body `{ email }`. `SecureRandom` 6자리 숫자 OTP 생성 → Caffeine `Cache<String, String>` (email → code) 5분 TTL 저장 → 이메일 발송(`JavaMailSender`). 이미 가입된 이메일이면 `409 DUPLICATE_RESOURCE` 반환(가입 흐름 조기 차단). `@PermitAll`(인증 불필요).
- [ ] **R2** `POST /api/v1/auth/email/verify` — body `{ email, code }`. 캐시에서 (email, code) 매칭 → 일치 시 검증 토큰(`verifiedEmail` 세션 마커) Caffeine 10분 TTL 보관 + 200 응답, 불일치 시 `400 INVALID_OTP`, 만료 시 `410 OTP_EXPIRED`. `@PermitAll`.
- [ ] **R3** OTP 발송 rate limit — 동일 email 1분 1회. 초과 시 `429 RATE_LIMIT_EXCEEDED`.
- [ ] **R4** 발송 이메일 본문 — 제목: `[DartCommons] 이메일 인증 코드`. 본문: `인증 코드: {code} (5분 이내 입력)`. 디자인 템플릿은 텍스트 plain/HTML 겸용(Thymeleaf 불필요, 인라인 HTML 허용).
- [ ] **R5** 인증 완료 검증 — 최종 `POST /auth/signup` 에서 `verifiedEmail` 마커 존재 여부 확인. 마커 없으면 `422 BUSINESS_RULE_VIOLATION(EMAIL_NOT_VERIFIED)`. 기존 `AuthService.signup()`에 가드 추가.

### Frontend

- [ ] **R6** `signup/page.tsx` — 이메일 제출 시 `POST /auth/email/send-otp` 호출 후 `/signup/verify`로 이동. 실패(`409`) 시 "이미 가입된 이메일" toast.error + 이동 차단.
- [ ] **R7** `signup/verify/page.tsx` — OTP 제출 시 `POST /auth/email/verify` 호출. 성공 시 `/signup/terms`, `400`/`410` 에러 메시지 표시. 재전송 버튼 클릭 시 `POST /auth/email/send-otp` 재호출 + 타이머 리셋.
- [ ] **R8** `lib/api/auth.ts` — `sendEmailOtp(email)`, `verifyEmailOtp(email, code)` 추가.

---

## 영향 범위

- **영향 레이어**: backend(`user/controllers`, `user/services`, `infrastructure/mail`) + frontend(`signup`, `signup/verify`)
- **DB 변경**: 없음 — 이메일 OTP는 계정 생성 전 단계로 Caffeine 인메모리 캐시만 사용. `users` 테이블 변경 불필요.
- **외부 계약**: JavaMailSender(`spring-boot-starter-mail` 이미 의존성 존재 여부 확인 필요). SMTP 서버 환경변수(`MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`) 주입.

### 수정/신규 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../user/services/EmailVerificationService.java` (신규) | R1·R2·R3 — OTP 생성/캐시/검증/rate limit |
| `backend/.../user/controllers/AuthController.java` | R1·R2 엔드포인트 추가 (기존 파일 확인 필요) |
| `backend/.../user/services/AuthService.java` | R5 — signup() EMAIL_NOT_VERIFIED 가드 추가 |
| `backend/.../infrastructure/mail/MailClient.java` (신규 또는 기존) | R4 이메일 발송 |
| `frontend/src/app/(auth)/signup/page.tsx` | R6 send-otp 호출 연동 |
| `frontend/src/app/(auth)/signup/verify/page.tsx` | R7 TODO 2건 해소 |
| `frontend/src/lib/api/auth.ts` | R8 함수 2개 추가 |

---

## 관련 패턴 / 과거 사례

- [[mvp-missing-endpoints]] 카드 #2·#3·#4 — Caffeine 캐시·rate limit·마스킹 패턴 동일 (phone verify)
- `user-auth-jwt-oauth2`(Done) — `AuthService.signup()` 진입점
- `security-hardening-mvp`(Done) — Caffeine rate limit 패턴 (`AtomicInteger` 카운터)
- `notification-dispatcher`(Done) — `infrastructure/mail` 존재 여부 확인

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| SMTP 스팸 경계 (대량 발송) | rate limit 1분 1회(R3) + 1일 발송량 모니터링 권장 |
| OTP 평문 로깅 | `EmailVerificationService` 내부 로그에 code 마스킹 필수 |
| 검증 토큰(verifiedEmail 마커) 탈취 | Caffeine 인메모리 한정 10분 TTL — 세션 간 공유 불가, 다중 인스턴스 주의(MVP 단일 인스턴스 가정) |
| 이미 가입된 이메일로 OTP 발송 시 계정 존재 노출 | R1에서 `409` 반환 — 이메일 열거 공격(email enumeration) 가능. MVP 수용(확인 기반 UX 목적) |

---

## 권장 구현 방향

- Wave 1: R1·R2·R3·R4·R5 (BE 일괄 — MailClient 존재 시 재사용, 미존재 시 신규)
- Wave 2: R6·R7·R8 (FE 연동)
- [[mvp-missing-endpoints]] Wave 1(phone verify)과 병렬 진행 가능 — 공유 코드 없음

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
