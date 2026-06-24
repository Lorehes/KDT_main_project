---
type: spec
status: Approved
created: 2026-06-16
updated: 2026-06-24
---

# 알림 채널 설정 완성 Spec (kakao-notification-channel)

> 상태: Draft → **Approved** (2026-06-24, dc-tech-review 승인)

## 배경 / 목적

가입 완료 후 "알림 채널 설정" 체크리스트 단계.
알림 인프라(`notification-dispatcher` Done)와 카카오 알림톡 클라이언트(`KakaoAlimtalkClient`)는
이미 구현됐으나, **dev/placeholder 모드에서 `send()` 실제 API 호출 시도 → 실패**,
**비즈메시지 채널 미승인 상태에서 운영 불가**, **EMAIL SMTP 환경변수 미설정**의 3가지 갭이 있다.

- **페르소나**: A~E 전체 — 알림은 서비스 핵심 가치 전달 채널
- **BM 티어**: Free·Pro(카카오/이메일), Premium(텔레그램 추가 — MVP 이후)

### 현황 (코드 기반 확인)

| 항목 | 상태 |
|------|------|
| 알림 설정 FE-BE 연결 (`PUT /notifications/settings`) | ✅ 완료 |
| 알림 설정 로드 (`GET /notifications/settings`) | ✅ 완료 |
| `NotificationDispatcher` 4단계 필터 | ✅ 완료 |
| `ChannelSender` KAKAO/EMAIL/TELEGRAM 라우팅 | ✅ 완료 |
| `KakaoAlimtalkClient` 인프라 클라이언트 | ✅ 구현됨 (RestClient + @Retryable) |
| `MailNotificationClient` 이메일 클라이언트 | ✅ 구현됨 (JavaMailSender) |
| `sendOtp()` dev 모드 (placeholder → 콘솔 출력) | ✅ 구현됨 |
| `send()` dev 모드 체크 | ❌ 미구현 — placeholder 모드에서 실제 API 호출 시도 → 실패 |
| 카카오 비즈메시지 채널 승인 | ❌ 미완료 (`senderKey`, `templateCode` placeholder 상태) |
| 알림톡 템플릿 심사 | ❌ 미완료 |
| SMTP 환경변수 설정 | ❌ 확인 필요 (`spring.mail.host` 미설정 시 부팅 실패) |
| TELEGRAM | ⏭ MVP 미지원 (FAILED 기록 후 종료) |

---

## 요구사항

### Wave 1 — 개발 환경 안정화

#### R1 — `KakaoAlimtalkClient.send()` dev 모드 추가 (BE)

`sendOtp()`와 동일한 패턴으로 `send()`에 placeholder 모드 분기 추가.

```java
if (isDevMode()) {
    log.info("[DEV] Kakao Alimtalk SKIP (placeholder mode) phone=[REDACTED]");
    return true;
}
```

이로써 `senderKey=placeholder` 개발 환경에서 KAKAO 채널 알림 발송 시 API 호출 없이 로그만 기록.

#### R2 — EMAIL SMTP 환경변수 확인 및 `.env.example` 보완 (BE/Infra)

`MailNotificationClient`는 `spring.mail.*` 설정이 없으면 부팅 실패.
현재 운영/개발 환경에 `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` 설정 여부 확인.
누락 시 `application-local.yml` 또는 `.env.example`에 필수 환경변수 추가.

개발 환경 권장:
- `spring.mail.host=smtp.gmail.com` + Gmail App Password (개인 테스트용)
- 또는 [Mailhog](https://github.com/mailhog/MailHog) Docker 컨테이너 (로컬 SMTP 모킹)

#### R3 — 알림 채널 설정 저장 후 성공 토스트 (FE)

`useUpdateNotificationSettings` `onSuccess` 콜백에 `toast.success("알림 설정이 저장됐습니다.")` 추가.
현재 저장 후 별도 피드백 없음.

---

### Wave 2 — 이메일 채널 E2E 검증

#### R4 — 이메일 알림 E2E 흐름 검증

SMTP 환경변수 설정 후 아래 흐름 검증:
1. 테스트 사용자 포트폴리오에 종목 등록
2. BE에서 해당 종목 공시를 수동으로 `AnalysisCompletedEvent` 발행
3. `NotificationDispatcher` → `ChannelSender.sendEmail()` → `MailNotificationClient.send()` 실행
4. `notification_logs` 테이블에 `status=SENT` 기록 확인
5. 수신 이메일 확인 (내용·면책문구 포함 여부)

#### R5 — 알림 이력 FE 표시 (`/notifications`)

현재 `/notifications/page.tsx`가 `GET /api/v1/notifications`로 실이력을 표시하는지 확인.
미연결 시 `useNotifications()` 훅 연결.

---

### Wave 3 — 카카오 알림톡 실운영 (비즈 채널 승인 후)

> ⚠️ 카카오 비즈메시지 알림톡 정식 발송은 **카카오 비즈 채널 승인 + 템플릿 심사**가 필요.
> Wave 3는 승인 완료 후 진행.

#### R6 — 카카오 비즈 채널 등록 및 템플릿 심사

필요 정보:
- 카카오 비즈니스 채널 개설 (`https://business.kakao.com`)
- 발신 프로필 키(`senderKey`) 발급
- 알림톡 템플릿 등록 및 심사 (공시 알림 1종 + OTP 1종)
- 템플릿 예시:
  ```
  [공시레이더] #{corp_name} (#{stock_code})
  #{sentiment_label} #{report_nm}
  #{summary}
  
  ※ 본 분석은 정보 제공 목적이며 투자 자문이 아닙니다.
  ```

#### R7 — 환경변수 설정 (BE)

```yaml
# application-prod.yml (환경변수로 주입)
dartcommons:
  kakao:
    alimtalk:
      base-url: ${KAKAO_ALIMTALK_BASE_URL}  # 카카오 비즈메시지 가이드 확인 필요
      api-key: ${KAKAO_ALIMTALK_API_KEY}
      sender-key: ${KAKAO_ALIMTALK_SENDER_KEY}
      template-code: ${KAKAO_ALIMTALK_TEMPLATE_CODE}
      otp-template-code: ${KAKAO_ALIMTALK_OTP_TEMPLATE_CODE:dc_otp_v1}
```

#### R8 — 알림톡 API endpoint 확인 및 request body 검증

`KakaoAlimtalkClient.SEND_PATH` 및 `AlimtalkRequest` 필드명은 "확인 필요" 주석이 있음.
카카오 비즈메시지 REST API 가이드 (`https://business.kakao.com/info/alimtalk`) 기준으로
실제 endpoint·request body 확인 후 수정 필요.

---

## 영향 범위

- **영향 레이어**: backend(`infrastructure/kakao/`, `notification/`) + frontend(`notifications/settings/`)
- **영향 파일**:
  - `backend/.../infrastructure/kakao/KakaoAlimtalkClient.java` — R1(dev 모드)
  - `backend/src/main/resources/application-local.yml` — R2(SMTP 설정)
  - `frontend/src/lib/api/notifications.ts` — R3(onSuccess 콜백)
  - `frontend/src/app/(app)/notifications/page.tsx` — R5(이력 FE 연결 확인)
- **DB 변경**: 없음 (`notification_logs` 기존 구조 활용)
- **외부 계약**:
  - Wave 1·2: 없음 (이메일만)
  - Wave 3: 카카오 비즈메시지 API (`dartcommons.kakao.alimtalk.*` 환경변수 4종)

---

## 관련 패턴 / 과거 사례

- [[notification-dispatcher]] Done — `AnalysisCompletedEvent` → `ChannelSender` 흐름
- [[notification-retry-job]] Done — PENDING 알림 재발송 패턴
- [[mvp-missing-endpoints]] Done — `GET/PUT /notifications/settings` 구현
- 기존 구현: `backend/.../infrastructure/kakao/KakaoAlimtalkClient.java` (R1 패턴: `sendOtp()` dev 모드 참고)
- 기존 구현: `backend/.../infrastructure/mail/MailNotificationClient.java` (R4 Email 발송)

## 리스크 / 법적 검토

| 리스크 | 대응 |
|--------|------|
| SMTP 미설정으로 부팅 실패 | R2에서 환경변수 확인. 테스트 yml에 dummy host 추가 |
| 알림톡 템플릿에 "매수·매도 권유" 표현 | 자본시장법 §11.1 — 면책 문구 필수, 투자 권유 표현 금지 |
| 전화번호 평문 로깅 | `KakaoAlimtalkClient`에서 이미 `phone=[REDACTED]` 처리됨 |
| 비승인 채널로 알림톡 발송 시 카카오 계정 정지 | Wave 3는 반드시 채널 승인 후 진행 |
| OTP 템플릿과 알림 템플릿 혼용 | `templateCode`/`otpTemplateCode` 별도 관리 (properties에 분리됨) |

## 권장 구현 방향

Wave 1(R1·R2·R3) → Wave 2(R4·R5) 순서로 즉시 진행 가능.
Wave 3는 카카오 비즈 채널 심사(수일~수주 소요) 후 진행.
**MVP 출시는 이메일 채널 기반(Wave 2 완료)으로 가능** — 카카오 알림톡은 추후 활성화.

## Tech Review (dc-tech-review · 2026-06-24)

> 검토 시점 코드 실측(SSOT: BE 코드) 결과, Spec(2026-06-16 작성) 대비 일부 항목이 이미 해소됨.
> 아래는 **현재 코드 기준 재검증** 결과다.

### 코드 실측 재검증 (Spec claim vs 현재 코드)

| Req | Spec 주장 | 현재 코드 실측 | 판정 |
|-----|----------|---------------|------|
| R1 | `send()` dev 모드 미구현 | `KakaoAlimtalkClient.send()`(L72-87) placeholder 분기 없음. `sendOtp()`(L103-106)에만 존재. `ChannelSender.sendKakao()`(L65-66)이 `send()` 후 무조건 `markSent()` → placeholder 모드에서 `send()`가 실 API 호출 시도→예외 | **유효 — 구현 필요** |
| R2 | SMTP 미설정 부팅 실패 | `application.yml`(L34-44)에 `MAIL_HOST:localhost`·port 1025(MailHog 기본)·auth=false 안전 기본값 존재 → **부팅 실패 위험 없음**. 단 `.env.example`에 `MAIL_*` 전무 + Kakao 변수명 불일치(yml `KAKAO_ALIMTALK_API_KEY`/`KAKAO_SENDER_KEY` vs .env.example `KAKAO_ALIMTALK_APP_KEY`/`KAKAO_ALIMTALK_SENDER_KEY`) | **부분 해소 — .env.example 정합만** |
| R3 | 저장 후 성공 토스트 없음 | `useUpdateNotificationSettings` `onSuccess`(notifications.ts L79)가 invalidate만, `toast.success` 없음 | **유효 — 구현 필요** |
| R4 | 이메일 E2E 검증 | SMTP 발송 검증은 GreenMail/MailHog 필요 — 코드 변경 아닌 수동/통합검증 | **수동 검증 — 별도 분리** |
| R5 | 알림 이력 FE 미연결 | `notifications/page.tsx`(L52,67)가 이미 `useNotifications()`→`data.content` 소비. **완료 상태** | **이미 완료 — no-op** |
| R6~R8 | 카카오 비즈채널/템플릿/endpoint | 외부 심사(카카오 비즈채널 승인) 의존 | **Wave 3 연기** |

### 아키텍처 분해

- 영향 레이어: backend(`infrastructure/kakao`) + 설정(`.env.example`) + frontend(`lib/api/notifications`, `notifications/settings`)
- 신규: 없음 (전부 기존 파일 수정)
- 수정: `KakaoAlimtalkClient.send()`(R1), `.env.example`(R2), `notifications.ts` `useUpdateNotificationSettings`(R3) + `settings/page.tsx` 저장 핸들러(R3 토스트 발화 경로)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `KakaoAlimtalkClient.send()` placeholder 분기 추가 (`sendOtp()` 패턴 동일) | backend/infrastructure | BE | 하 | - |
| 2 | `.env.example` `MAIL_*` 5종 추가 + Kakao 변수명 yml 정합 | infra/config | BE | 하 | - |
| 3 | `useUpdateNotificationSettings` `onSuccess` → `toast.success` 추가 | frontend | FE | 하 | - |

### DB / 마이그레이션 영향

- 없음. `notification_logs` 기존 구조 활용 (Spec §영향범위 일치).

### 외부 계약 영향

- Wave 1: 없음 (placeholder 모드는 외부 호출 안 함).
- Wave 3(연기): 카카오 비즈메시지 API endpoint·request body 실측 필요 — 채널 승인 후.

### 리스크 & 법적 검토

- **전화번호 평문 로깅 금지**(CLAUDE.md §7): R1 dev 로그는 반드시 `phone=[REDACTED]` 유지 — `sendOtp()`와 동일 패턴 강제.
- **투자 권유 표현 금지**(§7, 통합기획서 §11.1): R3는 설정 저장 토스트로 분석 문구 무관 — 해당 없음.
- 카카오 비즈채널 비승인 발송 시 계정 정지(Spec 리스크표) → Wave 3 반드시 승인 후.

### 예상 wave 수

- **Wave 1 (이번)**: R1·R2·R3 — 단일 wave (3파일, 난이도 하).
- **Wave 2**: R4(이메일 E2E 수동검증)·R5(완료) — R5 no-op, R4는 `/dc-test-verify` 또는 운영 검증으로 분리.
- **Wave 3**: R6~R8 — 카카오 비즈채널 승인 외부 의존, 별도 Spec/후속.

> 결론: Wave 1(R1·R2·R3) 즉시 구현 가능. R4는 SMTP 실환경 수동검증으로 분리, R5는 이미 완료.
> MVP 권장 충족 = Wave 1 완료 + 이메일 폴백 안전 동작 보장.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
