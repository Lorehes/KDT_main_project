---
type: spec
status: Done
created: 2026-06-08
updated: 2026-06-08
---

# notification-dispatcher Spec

> 상태: Draft → Approved → **Done** (2026-06-08, Wave 1~3 + RetryJob 완료)

## 배경 / 목적

- **문제**: Analysis Stage 2 결과가 DB에 적재되지만 사용자에게 전달되지 않음.
  `notification/` 도메인이 `package-info.java` 하나만 존재하는 빈 상태.
- **해결**: `AnalysisCompletedEvent` 구독 → 보유 포트폴리오 조회 → 알림 설정 필터
  → 카카오 알림톡(1순위) / 이메일(폴백) 발송 → `notifications` 테이블 기록.
- **대상 페르소나**: A(보유종목 즉시 모니터), B(직장인 퇴근 후 확인), C(시니어) — 통합기획서 §2
- **BM 티어**: Free(알림 1일 3건) / Pro(무제한) / Premium(무제한 + 다이제스트)

## 요구사항

- [ ] `AnalysisCompletedEvent`를 `@TransactionalEventListener(AFTER_COMMIT)`으로 구독
- [ ] 이벤트의 `withheld=true` 건 → 발송 스킵 (저신뢰도 공시)
- [ ] `portfolios.stock_code`로 보유 유저 역조회 (동일 종목 여러 유저 지원)
- [ ] 유저별 4단계 발송 자격 필터:
  - `notify_enabled = TRUE`
  - `notify_type_filter` vs `sentiment` 정합
    (POSITIVE_ONLY → POSITIVE만, NEGATIVE_ONLY → NEGATIVE만, ALL → 전체)
  - `off_hours_allowed = FALSE`이면 KRX 거래시간(월~금 09:00~15:30 KST) 외 스킵
  - `notify_frequency = 'INSTANT'` 인 유저만 즉시 발송
    (DAILY_1/DAILY_2/WEEKLY는 DigestDispatchJob 범위 — **MVP 외**)
- [ ] 채널 라우팅 (`notify_channel` 기준):
  - `KAKAO` → `KakaoAlimtalkClient` (WebClient)
  - `EMAIL` → `JavaMailSender` (Spring Mail)
  - `TELEGRAM` → 발송 없이 `FAILED` + error_message="TELEGRAM_NOT_IMPLEMENTED" 기록
- [ ] `notifications` 테이블에 PENDING 생성 → 발송 성공 시 SENT, 실패 시 FAILED
- [ ] `uq_notification_dedup(user_id, disclosure_id, channel)` 충돌 시 멱등 무시
  (`ON CONFLICT DO NOTHING` 또는 `DataIntegrityViolationException` catch)
- [ ] 알림 메시지에 면책 문구 포함:
  "본 내용은 AI 분석 요약으로, 투자 권유가 아닙니다."
- [ ] 발송 오류는 로그만 기록, 예외 전파 금지 (알림 실패가 분석 결과를 롤백하면 안 됨)

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(notification, infrastructure, user, analysis)
- **신규 파일**:
  - `backend/.../notification/entities/Notification.java`
  - `backend/.../notification/repositories/NotificationRepository.java`
  - `backend/.../notification/services/NotificationDispatcher.java`
  - `backend/.../notification/services/NotificationMessageBuilder.java`
  - `backend/.../notification/dto/NotificationMessage.java`
  - `backend/.../infrastructure/kakao/KakaoAlimtalkClient.java`
  - `backend/.../infrastructure/kakao/KakaoAlimtalkProperties.java`
  - `backend/.../infrastructure/mail/MailNotificationClient.java`
- **수정 파일**:
  - `backend/build.gradle` — `spring-boot-starter-mail` 추가
  - `backend/src/main/resources/application.yml` — Kakao Alimtalk + SMTP 프로퍼티 추가
  - `backend/.../user/entities/User.java` — `notify_frequency/type_filter/off_hours_allowed` 필드 추가 확인
- **DB 변경**: **없음** — V6(`notifications`), V7(user 알림설정 컬럼) 이미 존재
- **외부 계약**:
  - 카카오 알림톡 API (비즈메시지) — 알림톡 채널 인증 및 템플릿 승인 필요
  - SMTP 메일 서버 — SendGrid 또는 자체 SMTP

## 관련 패턴 / 과거 사례

- `AnalysisCompletedEvent` 발행: `backend/.../analysis/services/AnalysisOrchestrator.java`
- 외부 API WebClient 패턴: `backend/.../infrastructure/dart/DartApiClient.java` (재시도·타임아웃 패턴 참조)
- `@TransactionalEventListener(AFTER_COMMIT)` — 분석 트랜잭션 커밋 후 구독
  (BEFORE_COMMIT이면 알림 기록이 같은 TX에 포함되어 롤백 위험)

## 리스크 / 법적 검토

- **자본시장법(통합기획서 §11.1)**: 알림 메시지에 "매수/매도 추천" 표현 절대 금지.
  템플릿 고정 문구에 면책 문구 포함 필수.
- **카카오 알림톡 정책**: 채널 인증 + 비즈니스 계정 필수. 테스트 템플릿 코드로 MVP 진행.
  프로덕션 배포 전 템플릿 심사 통과 필요.
- **이메일 스팸**: SMTP 인증(SPF/DKIM) 미설정 시 스팸 처리. SendGrid 사용 권장.
- **동시성**: 동일 공시 여러 유저 → 병렬 발송 시 DB 경합.
  `uq_notification_dedup` 충돌을 멱등 처리하면 안전.
- **발송 예외 전파 금지**: 알림 실패가 분석 결과 저장을 방해하면 안 됨.
  `@Async` 또는 try-catch로 예외 격리 필수.
- **개인정보**: 알림 메시지에 매수가·수량 등 금융 개인정보 포함 금지.
  공시 종목명·요약만 포함.

## 권장 구현 방향

**INSTANT 즉시 발송만 MVP 범위** (DigestDispatchJob, NotificationRetryJob은 다음 Spec):
- `NotificationDispatcher`에 `@TransactionalEventListener(AFTER_COMMIT)` → `@Async` 조합
  (AFTER_COMMIT이므로 별도 트랜잭션 필요 → `@Transactional(propagation = REQUIRES_NEW)`)
- 유저별 직렬 처리로 시작 (병렬화는 성능 이슈 시 후속 최적화)
- Kakao Alimtalk은 WebClient로 REST 호출 (별도 SDK 없음)
- 이메일 폴백은 `JavaMailSender`(Spring Boot 자동 구성) 사용
- `KakaoAlimtalkProperties` — `@ConfigurationProperties("dartcommons.kakao.alimtalk")`
- 거래시간 판단: `ZonedDateTime.now(ZoneId.of("Asia/Seoul"))` → 요일·시간 체크
  (공휴일 API MVP 제외 — 평일 기준으로만 판단)

**환경변수 추가 (MVP 최소)**:
```
KAKAO_ALIMTALK_API_KEY    # 카카오 비즈메시지 API 키
KAKAO_SENDER_KEY          # 알림톡 발신 프로필 키
KAKAO_ALIMTALK_TEMPLATE_CODE  # 승인된 알림톡 템플릿 코드
MAIL_HOST                 # SMTP 호스트 (예: smtp.sendgrid.net)
MAIL_PORT                 # 465 or 587
MAIL_USERNAME             # SMTP 인증 사용자
MAIL_PASSWORD             # SMTP 인증 패스워드
MAIL_FROM                 # 발신자 주소 (예: noreply@dartcommons.com)
```

## Tech Review (dc-tech-review · 2026-06-08)

### 아키텍처 분해

- **영향 레이어**: backend(notification, infrastructure, shared, user)
- **Frontend 영향 없음** — 이번 Spec은 백엔드 발송 파이프라인만. 알림 목록 UI는 별도 Spec.
- **신규 클래스**:
  - `notification/entities/NotificationEntity.java` — V6 notifications 테이블 JPA 엔티티
  - `notification/repositories/NotificationRepository.java` — PENDING·RETRYING 조회, 멱등 insert
  - `notification/services/NotificationDispatcher.java` — AFTER_COMMIT 이벤트 리스너 + 발송 오케스트레이션
  - `notification/services/NotificationMessageBuilder.java` — 채널별 메시지 + 면책 문구 빌더
  - `notification/dto/NotificationMessage.java` — 채널 불가지론 메시지 record
  - `infrastructure/kakao/KakaoAlimtalkClient.java` — WebClient REST 발송
  - `infrastructure/kakao/KakaoAlimtalkProperties.java` — `@ConfigurationProperties("dartcommons.kakao.alimtalk")`
  - `infrastructure/mail/MailNotificationClient.java` — `JavaMailSender` 래퍼
  - `shared/util/TradingHoursUtil.java` — KRX 거래시간(월~금 09:00~15:30 KST) 판단 (feature_structure §1.1)
- **수정 파일**:
  - `backend/build.gradle` — `spring-boot-starter-mail` 추가
  - `backend/src/main/resources/application.yml` — `dartcommons.kakao.alimtalk.*`, `spring.mail.*` 추가

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `build.gradle` mail 의존성 + `application.yml` Kakao Alimtalk·SMTP 프로퍼티 추가 | backend | BE | 하 | - |
| 2 | `KakaoAlimtalkProperties` + `KakaoAlimtalkClient` (WebClient, 타임아웃·재시도) | infra/kakao | BE | 중 | #1 |
| 3 | `MailNotificationClient` (`JavaMailSender` 래퍼, MIME 메시지) | infra/mail | BE | 하 | #1 |
| 4 | `TradingHoursUtil` — 월~금 09:00~15:30 KST 판단 (`shared/util/`) | shared | BE | 하 | - |
| 5 | `NotificationEntity` + `NotificationRepository` (V6 테이블 매핑, PENDING 생성) | notification | BE | 하 | - |
| 6 | `NotificationMessageBuilder` — 면책 문구 포함 채널별 메시지 빌드 | notification | BE | 하 | #5 |
| 7 | `NotificationDispatcher` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + 4단계 필터 + INSTANT 채널 라우팅 | notification | BE | 상 | #2,#3,#4,#5,#6 |
| 8 | `NotificationDispatcherIntegrationTest` — Testcontainers, MockBean 채널 클라이언트 | test | BE | 중 | #7 |

### DB / 마이그레이션 영향

- **신규 마이그레이션 없음** — V6(`notifications` 테이블, `uq_notification_dedup`) + V7(users 알림 설정 컬럼) 이미 존재
- `PortfolioEntity` 기반 `findUsersByStockCode(stockCode)` 쿼리 추가 필요 (기존 repository 수정)

### 외부 계약 영향

- **카카오 알림톡 API**: 비즈메시지 REST API (`https://alimtalk-api.kakao.com`). MVP는 테스트 템플릿으로 진행. 프로덕션 배포 전 템플릿 심사 + 채널 인증 필수.
- **SMTP (이메일 폴백)**: Spring Boot `spring.mail.*` 자동 구성. SendGrid SMTP 사용 권장 (SPF/DKIM 인증).
- DART/KRX/LLM 계약 변경 없음.

### 리스크 & 법적 검토

- **[HIGH] 자본시장법**: 알림 메시지에 투자 권유 표현 절대 금지 (통합기획서 §11.1). `NotificationMessageBuilder`에 면책 문구 하드코딩 — 템플릿 수정 시 법무 검토 필요.
- **[HIGH] 예외 전파**: `@Async` 콘텍스트에서 발송 예외가 analysis TX 롤백을 유발하면 안 됨. `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`가 이미 TX 분리를 보장하나, dispatcher 내부 예외도 try-catch로 격리 필수.
- **[MEDIUM] Kakao 알림톡 정책**: 미승인 템플릿 코드 사용 시 발송 거부. 테스트 환경에서 채널 클라이언트를 MockBean으로 대체 필수.
- **[MEDIUM] 개인정보**: `phone_number_enc`(AES 암호화 저장)를 알림톡 수신번호로 사용 시 `AesGcmEncryptor`로 복호화 필요. 평문 로깅 절대 금지.
- **[LOW] 공휴일 미처리**: MVP는 평일(월~금) 기준으로만 거래시간 판단. 공휴일에도 알림이 발송될 수 있음 — 추후 공공 API 연동으로 개선.

### 예상 wave 수

- **Wave 1**: 카드 #1~#4 — 인프라 기반 (build.gradle, 클라이언트, TradingHoursUtil)
- **Wave 2**: 카드 #5~#7 — 알림 도메인 핵심 (엔티티·서비스·디스패처)
- **Wave 3**: 카드 #8 — 통합 테스트 (Testcontainers)
- 총 3 wave
