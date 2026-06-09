---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# 결제 PG 연동 Spec (카카오페이)

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

`/checkout` 페이지는 현재 완전 정적 mockup(`alert("실결제 연동 후 활성화")`)이다. MVP 이후 단계에서 실제 결제를 연동해 Pro/Premium 구독을 유료화한다.

- **현황**: `/checkout`, `/checkout/new` UI mockup 완성. PG사 미선정.
- **목표**: 카카오페이 정기결제(자동 결제) 연동. 7일 무료 체험 후 자동 청구.
- **BM**: Pro ₩9,900/월, Premium ₩29,900/월 (통합기획서 §3.3)
- **제약**: api_spec.md §2.6 — MVP 결제 미연동 결정. 본 Spec은 MVP 이후 사업화 단계.

---

## 요구사항

### 백엔드 (신규 Spec 필요 — 현재 미구현)

- [ ] **R1** 결제 엔드포인트 설계 — `POST /payments/subscribe` (플랜 선택 + 카카오페이 ready)
- [ ] **R2** 카카오페이 정기결제 Ready — 카카오페이 API `POST /v1/payment/ready` 호출 → `tid` + 결제 승인 URL 반환
- [ ] **R3** 결제 승인 콜백 처리 — `POST /payments/approve` (카카오페이 `pg_token` 수신 → approve API 호출)
- [ ] **R4** 구독 상태 갱신 — 결제 성공 시 `users.tier` 업데이트 + `tier_expires_at` 설정
- [ ] **R5** 정기 결제 배치 — `@Scheduled` 월 1회 카카오페이 정기결제 SID로 자동 청구
- [ ] **R6** 결제 실패 처리 — 결제 실패 시 tier 유지 + 사용자 알림 발송(이메일)
- [ ] **R7** 환불·해지 — `DELETE /payments/subscribe` → 카카오페이 정기결제 비활성화 + 기간 만료 후 Free 전환

### 프론트엔드

- [ ] **R8** `/checkout` 실 결제 플로우 — "무료 체험 시작" 버튼 → `POST /payments/subscribe` → 카카오페이 결제 URL로 리다이렉트
- [ ] **R9** 결제 완료 콜백 라우트 — `app/api/payment/callback/route.ts` — `pg_token` 수신 → `/payments/approve` 호출 → `/dashboard?plan=pro`
- [ ] **R10** `/checkout/new` 카드 입력 — 실제 카드 입력은 카카오페이 SDK iframe으로 대체 (PCI-DSS)
- [ ] **R11** 마이페이지 구독 해지 — `/settings`에서 "구독 해지" 버튼 → `DELETE /payments/subscribe` 확인

---

## 영향 범위

- **영향 레이어**: backend(신규 `payment` 도메인) + frontend(`/checkout`, `/settings`)
- **DB 변경**: 신규 `payments` 테이블 + `subscriptions` 테이블 필요 → Flyway 마이그레이션
- **외부 계약**: 카카오페이 개발자 센터 가맹점 등록 + API 키 발급 (`KAKAOPAY_SECRET_KEY`, `KAKAOPAY_CID`)

### DB 마이그레이션 (신규)

```sql
-- V{n}__create_payments.sql
CREATE TABLE subscriptions (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES users(id),
  tier         VARCHAR(20) NOT NULL,
  sid          VARCHAR(100),          -- 카카오페이 정기결제 SID
  status       VARCHAR(20) NOT NULL DEFAULT 'TRIAL',  -- TRIAL|ACTIVE|CANCELED|EXPIRED
  trial_ends_at TIMESTAMPTZ,
  next_bill_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## 관련 패턴 / 과거 사례

- `user-auth-jwt-oauth2` (Done) — 외부 인증 API 연동 패턴 참고
- `api_spec.md §2.6` — MVP 결제 미연동 결정 근거 기록됨
- `notification-dispatcher` (Done) — 알림 발송 패턴 (결제 실패 알림에 재사용)

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| PCI-DSS 카드 정보 자체 처리 | 절대 금지. 카카오페이 SDK iframe만 사용 |
| 정기결제 중복 청구 | `subscriptions.sid` UNIQUE 제약으로 방어 |
| 무료 체험 후 미청구 | `TRIAL` 상태 `next_bill_at` 배치가 7일 후 자동 처리 |
| 환불 정책 | 자본시장법 투자서비스와 별개. 전자상거래법 청약철회 14일 적용 |
| API 키 하드코딩 금지 | `KAKAOPAY_SECRET_KEY`는 환경변수로만 주입 (CLAUDE.md §7) |

---

## 권장 구현 방향

- 백엔드에 `payment/` 도메인 모듈 신설 (controllers/services/repositories/dto)
- 카카오페이 API 호출은 `infrastructure/` WebClient로 격리 + 타임아웃·재시도
- 정기결제 SID는 AES-256-GCM 암호화 저장 권장 (매수가 암호화 패턴 동일)
- FE 결제 플로우: POST → 리다이렉트 URL → 카카오페이 → 콜백 → 완료

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
