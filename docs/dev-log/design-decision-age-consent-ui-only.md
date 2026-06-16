---
type: doc
status: done
created: 2026-06-16
updated: 2026-06-16
---

# 설계 결정: 만 14세 확인(AGE) 동의를 DB에 저장하지 않은 이유

> **관련 화면**: 회원가입 Step 2/4 약관동의 (`/signup/terms`)
> **관련 파일**: `frontend/src/app/(auth)/signup/terms/page.tsx`, `backend/.../db/migration/V8__create_consent_logs.sql`

---

## 결정 요약

TERMS / PRIVACY / DISCLAIMER / MARKETING 4종은 DB(`users` 테이블 및 `consent_logs`)에 저장하지만,
**만 14세 이상 확인(AGE) 항목은 UI 전용으로만 처리하고 DB에 저장하지 않는다.**

---

## 이유

### 1. 법적 성격이 다르다

나머지 4종은 **약관에 대한 동의(consent)** 로, 이용자가 특정 약관 내용에 동의한 사실을 법적으로 기록해야 한다.
`consent_logs` 테이블의 CHECK 제약이 이를 명시한다:

```sql
CHECK (consent_type IN ('TERMS','PRIVACY','DISCLAIMER','MARKETING'))
```

반면 만 14세 확인은 **사실 확인 선언**이다. 이용자가 "나는 만 14세 이상입니다"라고 자기 선언하는 것이며,
약관 동의와 달리 서비스가 별도로 보관·증명해야 할 법적 의무가 현 단계에서는 없다.

### 2. 체크박스로는 실질적 검증이 불가능하다

만 14세 여부를 실제로 증명하려면 주민등록번호 또는 공공 PASS 인증이 필요하다.
체크박스를 DB에 저장해도 "이용자가 체크했다"는 사실만 남을 뿐,
실제로 14세 이상임을 보장하지 못하므로 법적 효력이 없다.
저장 비용 대비 보호 효과가 없다.

---

## 현재 처리 방식

```
[UI] AGE 체크박스 → checked 상태 로컬 관리 → 필수 항목 충족 여부 판단에만 사용
                                               → POST /auth/signup API에 미전송
                                               → DB 미저장
```

필수 항목 전체 동의 버튼은 AGE를 포함한 필수 4종을 함께 토글하지만,
API 전송 및 DB 저장 대상은 TERMS / PRIVACY / DISCLAIMER 3종(+선택 MARKETING)만이다.

---

## 향후 확장 시점

다음 조건 중 하나라도 충족되면 재검토가 필요하다:

| 조건 | 대응 방안 |
|------|-----------|
| 본인인증(PASS/KMC) 도입 | 인증 결과에 생년월일 포함 → `users.birth_verified_at` 추가 또는 `consent_logs`에 `AGE_VERIFIED` 타입 추가 |
| 아동·청소년 대상 서비스 확장 | 정보통신망법 제31조 법정대리인 동의 프로세스 별도 설계 필요 |
| 개인정보 감독기관 지적 | `consent_logs`에 `AGE_VERIFIED` 타입 추가로 대응 가능 |

---

## 관련 결정 문서

- [[postmortem-fe-be-misalignment]] — 약관 동의 API 스키마 정합성 이슈
- `backend/src/main/resources/db/migration/V8__create_consent_logs.sql` — consent_logs 설계 의도
