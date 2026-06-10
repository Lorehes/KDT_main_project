---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-10
---

# MVP 미구현 엔드포인트 Spec (phone verify · consents · pricing plans)

> 상태: Draft → **Approved** (2026-06-10, dc-tech-review 승인 · C1 이메일 verify [[auth-email-verify]]로 분리)

## 배경 / 목적

`docs/개발명세서/api_spec.md` 에 정의됐으나 백엔드에 구현되지 않은 엔드포인트 3종이 MVP 회원가입/법적/요금제 페이지를 차단하고 있다. FE 는 TODO 주석으로 대응 중이다. 본 Spec 은 3개 엔드포인트를 일괄 추가한다.

- **현황**: FE `signup/phone/page.tsx:43,48` TODO 2건. `app/(public)/pricing` 정적 mockup. 회원가입 약관 동의 후 DB 로깅 없음. (`signup/verify/page.tsx` 이메일 OTP TODO는 [[auth-email-verify]]로 분리됨)
- **목표**: phone OTP 인증, consent 로깅(법적 요건), pricing plans 동적 응답 추가
- **BM 연관**: Free 가입 플로우 + Pro/Premium 업그레이드 UI 데이터 소스

---

## 요구사항

### Phone Verify

- [ ] **R1** `POST /api/v1/users/me/phone/verify` (인증번호 발송) 엔드포인트 추가 — body `{ phoneNumber }`. SMS 발송은 외부 SMS 게이트웨이(Aligo/카카오 알림톡 OTP 템플릿). Caffeine 5분 TTL 로 (userId, code) 저장
- [ ] **R2** `POST /api/v1/users/me/phone/verify/confirm` 엔드포인트 — body `{ code }`. 캐시에서 매칭 + 검증 성공 시 `users.phone_number_enc` AES 암호화 저장, `phone_verified=true` 플래그
- [ ] **R3** SMS 발송 rate limit — 동일 userId 1분 1회, 시간당 5회
- [ ] **R4** FE `signup/phone/page.tsx`, `signup/verify/page.tsx` TODO 4건 해소 — 실제 API 호출로 교체

### Consent

- [ ] **R5** `POST /api/v1/consents` 엔드포인트 — body `{ termsVersion, privacyVersion, marketingOptIn }`. `consent_logs` 테이블에 row 추가(V8 마이그레이션 이미 존재)
- [ ] **R6** `GET /api/v1/consents/status` 엔드포인트 — 사용자의 최신 동의 상태 조회. 약관 버전 미일치 시 `requiresRenewal=true` 반환
- [ ] **R7** OAuth 신규 가입 분기 — `requiresRenewal=true` 응답 시 FE 가 `/signup/terms` 로 강제 이동 ([[frontend-oauth-social]] R7 연동)

### Pricing Plans

- [ ] **R8** `GET /api/v1/pricing/plans` 엔드포인트 — 응답 `[{ tier, price, currency, features[], recommendedFor, monthlyFreeQuota }]`. 통합기획서 §3.3 BM 데이터 기반
- [ ] **R9** `application.yml` 또는 `system_configs` 테이블에서 가격 정보 외부화 — 코드 하드코딩 금지. 가격 변경 시 코드 수정 없이 운영 가능
- [ ] **R10** FE `app/(public)/pricing/PricingClient.tsx` 정적 mockup → API 호출로 교체. SSR 캐시 60초

---

## 영향 범위

- **영향 레이어**: backend (`user`, `shared/config`) + frontend (`signup/phone`, `signup/verify`, `pricing`)
- **DB 변경**: 없음 (consent_logs 는 V8 기존, phone_verified 는 users 컬럼 추가 필요 시 확인)
- **외부 계약**: SMS 게이트웨이(Aligo 또는 카카오 알림톡 OTP) 신규 연동

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../user/controllers/UserController.java` | R1·R2 phone verify 엔드포인트 |
| `backend/.../user/services/PhoneVerificationService.java` (신규) | R1·R2·R3 |
| `backend/.../infrastructure/sms/SmsClient.java` (신규) | R1 외부 SMS 게이트웨이 |
| `backend/.../user/controllers/ConsentController.java` (신규) | R5·R6 |
| `backend/.../user/services/ConsentService.java` | R5·R6 (기존 서비스 확장) |
| `backend/.../user/controllers/PricingController.java` (신규) | R8 |
| `backend/.../shared/config/PricingProperties.java` (신규) | R9 |
| `backend/src/main/resources/db/migration/V20__add_phone_verified_to_users.sql` (확인 후 신규) | R2 |
| `frontend/src/app/(auth)/signup/phone/page.tsx` | R4 TODO 해소 |
| `frontend/src/app/(auth)/signup/verify/page.tsx` | R4 TODO 해소 |
| `frontend/src/lib/api/auth.ts` | `verifyPhone`, `confirmPhone`, `postConsent`, `getConsentStatus` 추가 |
| `frontend/src/lib/api/pricing.ts` (신규) | R8 클라이언트 |
| `frontend/src/app/(public)/pricing/PricingClient.tsx` | R10 |

---

## 관련 패턴 / 과거 사례

- `user-auth-jwt-oauth2` (Done) — 인증 흐름, AES 암호화 패턴 (phone_number_enc 동일)
- `notification-dispatcher` (Done) — 카카오 알림톡 클라이언트 — OTP 템플릿 재사용 가능
- `docs/개발명세서/api_spec.md` §2.1 — phone verify 명세 (기존)
- `docs/개발명세서/db_schema.md` — consent_logs 테이블 (V8)
- 통합기획서 §11.2 — 법적 요건: 정보통신망법 §22 동의 로깅

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| SMS 발송 비용 폭증 | rate limit 1분 1회, 시간당 5회 + 모니터링 대시보드 |
| OTP 평문 캐시 노출 | Caffeine 인메모리 + 5분 TTL + SecretMasker 로 로그 마스킹 |
| 약관 버전 변경 시 기존 사용자 일괄 동의 누락 | `consents/status` 의 `requiresRenewal=true` 응답으로 강제 흐름 + 미동의 시 일부 기능 제한 |
| 가격 정보 외부화 후 캐시 미반영 | `system_configs` 변경 후 명시적 `@CacheEvict` 트리거 — Admin 엔드포인트 추가 검토 |
| phone_verified 미완료 사용자의 알림 발송 | NotificationDispatcher 가 이미 `phone_number_enc != null` 가드 — 본 Spec 으로 phone_verified 플래그 추가 시 동시 가드 |

---

## 권장 구현 방향

- Wave 1 (Phone Verify): R1·R2·R3·R4 — SMS 게이트웨이 PoC 우선 (Aligo 무료 트라이얼)
- Wave 2 (Consent): R5·R6·R7 — 기존 `ConsentService` 확장
- Wave 3 (Pricing): R8·R9·R10 — 가장 단순, 독립 진행 가능
- [[frontend-oauth-social]] R7 (소셜 가입 시 약관 동의 분기) 와 R7 정합 — 동일 흐름
- SMS 게이트웨이 선택은 별도 의사결정 — 본 Spec Tech Review 단계에서 확정

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

---

## Tech Review (dc-tech-review · 2026-06-10)

### 아키텍처 분해

- **영향 레이어**:
  - backend: `user`(controllers/services/dto/entities/repositories), `shared/config`, `infrastructure/sms`(신규) 또는 `infrastructure/kakao`(재사용)
  - frontend: `app/(auth)/signup/phone`, `app/(public)/pricing`, `lib/api/auth.ts`, `lib/api/pricing.ts`(신규)
- **신규 클래스/컴포넌트**:
  - BE: `PhoneVerificationService`, `PhoneVerifyRequest/ConfirmRequest`, `ConsentController`, `ConsentRequest/StatusResponse`, `PricingController`, `PricingProperties`, `PricingResponse`, `SmsClient`(인터페이스)
  - FE: `lib/api/pricing.ts`, `lib/api/consent.ts`
- **수정 대상**:
  - BE: `UserController`(phone verify 엔드포인트 2개 추가), `ConsentService`(`getStatus` 메서드 추가), `UserEntity`(phone_verified 컬럼), `UserMeResponse`(phone_verified 반영), `AuthService`(회원가입 시 phone_verified=false 기본)
  - FE: `signup/phone/page.tsx`, `pricing/PricingClient.tsx`, `lib/api/auth.ts`

### Spec 정정 사항 (구현 전 확인 필요)

- **C1 (R4 분해)**: `signup/verify/page.tsx`는 **이메일 OTP 화면**(`POST /auth/email/verify`)으로 phone 인증과 별개. 본 Spec 범위는 **phone 흐름만** 포함 권장. 이메일 verify TODO 2건은 별도 Spec(`auth-email-verify` 신규) 또는 `user-auth-jwt-oauth2` 후속으로 분리.
- **C2 (phone_verified 컬럼 vs 파생)**: 현재 `UserMeResponse` 주석은 "phoneNumberEnc != null 여부로 판정"로 파생 필드 가정. Spec R2는 명시 컬럼 요구 — **명시 컬럼 채택**(번호 등록 ≠ 인증 완료 분리, 재인증 흐름 대비). V17 마이그레이션 신규.
- **C3 (SMS 게이트웨이 선택)**: Spec은 Aligo/카카오 알림톡 OTP 양자 미확정. `KakaoAlimtalkClient` 이미 존재 — **재사용 권장**(Alimtalk 비즈니스 채널 기반 OTP 템플릿 등록). Aligo는 별도 계약/비용 추가. Wave 1 의사결정 필요.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | V17__add_phone_verified_to_users.sql + UserEntity 컬럼 매핑 | backend/user | BE | 하 | - |
| 2 | `PhoneVerificationService` — Caffeine `Cache<Long, OtpEntry>` (5분 TTL), 6자리 코드 SecureRandom 생성, 검증 + AES 암호화 저장 + `phone_verified=true` 갱신 | backend/user | BE | 중 | #1 |
| 3 | SMS 발송 어댑터 — `KakaoAlimtalkClient` OTP 템플릿 메서드 추가 (`sendOtp(phone, code)`) | backend/infrastructure | BE | 중 | C3 결정 |
| 4 | Rate limit — `Caffeine<Long, AtomicInteger>` 1분(1회) + 시간당(5회) 2단 카운터, 초과 시 `429 RATE_LIMIT_EXCEEDED` | backend/user | BE | 중 | #2 |
| 5 | `UserController` — `POST /users/me/phone/verify`, `POST /users/me/phone/verify/confirm` 핸들러 | backend/user | BE | 하 | #2·#3·#4 |
| 6 | `UserMeResponse.phone_verified` snake_case 노출 + `AuthService.signup()`에서 기본 false | backend/user | BE | 하 | #1 |
| 7 | `ConsentController` — `POST /consents`, `GET /consents/status` 핸들러 (200 응답 스키마는 api_spec.md §2.x 확인 필요) | backend/user | BE | 하 | - |
| 8 | `ConsentService.getStatus(userId)` — `consent_logs` MAX(agreed_at) per type + 현재 `CURRENT_POLICY_VERSION` 대조 → `requiresRenewal` 산출 | backend/user | BE | 중 | - |
| 9 | `PricingController` + `PricingProperties` (`@ConfigurationProperties("pricing")`) — application.yml의 plans 리스트를 record로 매핑, `@Cacheable("pricing-plans")` 60초 TTL | backend/user, shared/config | BE | 하 | - |
| 10 | FE `lib/api/auth.ts` — `verifyPhone()`, `confirmPhone()` 추가 + TanStack `useMutation` 훅 (`onError` toast.error 표준) | frontend/lib | FE | 하 | #5 |
| 11 | FE `signup/phone/page.tsx` — TODO 2건 해소: 발송/검증 mutation 연결, `429`/`400 INVALID_OTP` 에러 표시, 성공 시 `/signup/profile` 이동 | frontend/signup | FE | 중 | #10 |
| 12 | FE `lib/api/consent.ts` + `signup/terms` mutation 연결 (consent record 저장) | frontend/lib | FE | 하 | #7 |
| 13 | FE `lib/api/pricing.ts` + `pricing/PricingClient.tsx` 정적 mockup → `useQuery` 교체, `staleTime: 60_000` | frontend/pricing | FE | 하 | #9 |
| 14 | 통합 테스트 — Testcontainers 기반: phone verify happy/실패/rate limit, consent record/status renewal, pricing plans 응답 스키마 | backend/test | BE | 중 | #5·#7·#9 |

### DB / 마이그레이션 영향

- **신규 파일**: `backend/src/main/resources/db/migration/V17__add_phone_verified_to_users.sql`
  ```sql
  ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
  COMMENT ON COLUMN users.phone_verified IS 'phone_number_enc 인증 완료 여부 — 알림톡 발송 가드(통합기획서 §11.1).';
  ```
- `consent_logs`(V8): 변경 없음 — `policy_version` 컬럼 + 인덱스 그대로 사용. `ConsentService.CURRENT_POLICY_VERSION = "v1.0"`을 `getStatus`에서 대조.
- `system_configs`(V11): **이번 Spec에서는 미사용** — pricing은 application.yml `@ConfigurationProperties` 채택. system_configs는 잡 상태 저장 용도로 의미가 분리되어 있어 가격 정보까지 끌어오면 책임이 흐려짐. 추후 Admin 가격 변경 요구 시 별도 Spec.
- 인덱스 영향 없음.

### 외부 계약 영향

- **카카오 알림톡 OTP 템플릿**: 비즈니스 채널에 OTP용 신규 템플릿 등록 필요(예: `dc_otp_v1` — "[DartCommons] 인증번호 {{code}}, 5분 내 입력"). 운영자 카카오 비즈니스 콘솔 작업 — 코드 외 의존성.
- **DART/KRX**: 영향 없음.
- **자체 REST**: `/api/v1/users/me/phone/verify`, `/verify/confirm`, `/consents`, `/consents/status`, `/pricing/plans` 5개 신규 — api_spec.md 응답 스키마 §2.1·§2.6 보강 필요.
- **FE→BE 응답 스키마**: snake_case 직렬화 표준(이전 [[fe-be-alignment]] 결정) 준수 — `phone_verified`, `requires_renewal`, `monthly_free_quota`.

### 리스크 & 법적 검토

| 리스크 | 대응 |
|------|------|
| **SMS 비용 폭증** (자동화 봇 공격) | Caffeine 2단 rate limit(#4) + 동일 phoneNumber 다중 userId 시도 차단(IP 기반 추가 검토) + 일일 발송량 상한 알림 |
| **OTP 평문 캐시·로그 노출** | Caffeine 인메모리 + 5분 TTL + `JwtAuthenticationFilter` WARN 로그 패턴 따라 phone/code 마스킹 헬퍼 추가 |
| **개인정보 — 휴대폰 번호 평문 로깅** (CLAUDE.md §7) | AES-256-GCM 암호화 저장(기존 `EncryptionService` 재사용), 평문은 컨트롤러 → 서비스 사이 메서드 시그니처에서만 통과, 로깅 절대 금지 |
| **약관 버전 변경 시 일괄 재동의 누락** (자본시장법·정보통신망법 §22) | `consents/status.requires_renewal` 응답으로 FE 강제 흐름. OAuth 신규 가입은 [[frontend-oauth-social]] R7과 동일 분기 |
| **phone_verified=false 상태에서 알림톡 발송 시도** | `NotificationDispatcher`(기존)에서 `phone_number_enc != null` 가드 → `phone_verified = true` 가드로 강화 (별도 카드 분리 권장, 본 Spec 범위 외) |
| **Pricing application.yml 가격 변경 시 무중단 미반영** | `@Cacheable` 60초 TTL — 1분 내 자연 만료. 즉시 반영 필요 시 Admin `@CacheEvict` 엔드포인트는 후속 Spec |
| **자본시장법 표현** (CLAUDE.md §7) | 본 Spec은 인증/동의/요금 — 직접 충돌 없음. pricing features 문구는 "투자 권유"성 표현 회피(`recommendedFor: "장기 보유 투자자"` 수준 유지) |

### 예상 wave 수

- **Wave 1 (Phone Verify)**: 카드 #1·#2·#3·#4·#5·#6·#10·#11 — SMS 게이트웨이 C3 결정 + OTP 발송/검증/저장 일괄. 가장 무거움(난이도 중×4)
- **Wave 2 (Consent)**: 카드 #7·#8·#12 — `ConsentService` 확장 + 컨트롤러/FE
- **Wave 3 (Pricing)**: 카드 #9·#13 — 가장 단순, Wave 1·2와 병렬 가능
- **Wave 4 (Tests)**: 카드 #14 — Testcontainers 통합 테스트 일괄
- 총 **4 wave**, R4의 이메일 verify 부분은 본 Spec에서 제외(C1).
