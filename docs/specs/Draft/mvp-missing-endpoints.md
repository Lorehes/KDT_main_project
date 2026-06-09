---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# MVP 미구현 엔드포인트 Spec (phone verify · consents · pricing plans)

> 상태: **Draft** (dc-plan 생성, 수동 분석에서 발견된 미구현 API)

## 배경 / 목적

`docs/개발명세서/api_spec.md` 에 정의됐으나 백엔드에 구현되지 않은 엔드포인트 3종이 MVP 회원가입/법적/요금제 페이지를 차단하고 있다. FE 는 TODO 주석으로 대응 중이다. 본 Spec 은 3개 엔드포인트를 일괄 추가한다.

- **현황**: FE `signup/verify/page.tsx:41,49` · `signup/phone/page.tsx:43,48` TODO 4건. `app/(public)/pricing` 정적 mockup. 회원가입 약관 동의 후 DB 로깅 없음
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
