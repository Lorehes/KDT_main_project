---
type: spec
status: Draft
created: 2026-06-08
updated: 2026-06-08
---

# notification-retry-job Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

- **문제**: `NotificationDispatcher`는 발송 실패 시 `status=FAILED`로 기록하고 종료.
  일시적 오류(카카오 API 타임아웃, 메일 서버 불응답 등)로 FAILED가 된 건은 재발송 경로가 없어 사용자가 알림을 영구 미수신.
  또한 `markSent()` 후 save() 실패 시 record가 `PENDING` 고착 — 재발송 시 이중 발송 위험.
- **해결**: `@Scheduled` 배치 잡으로 `PENDING`(고착) + `RETRYING` 상태를 주기적으로 재발송.
  `sent_at IS NULL` + `retry_count ≤ MAX_RETRY` 조건으로 이중 발송 방지.
- **대상 페르소나**: A(보유종목 즉시 모니터), B(직장인 퇴근 후 확인) — 알림 미수신 최소화
- **BM 티어**: Free/Pro/Premium 공통 (재시도는 인프라 안정성 레이어)

## 요구사항

- [ ] `NotificationRetryJob` — `@Scheduled` 배치 잡, 5분 주기 (`fixedDelay=300_000`)
- [ ] 재발송 대상 조건: `status IN ('PENDING', 'RETRYING') AND sent_at IS NULL AND retry_count < 3`
  - `sent_at IS NOT NULL`이면 이미 발송 성공 → PENDING 고착이 아니므로 스킵
  - `retry_count >= 3`이면 FAILED 확정 후 스킵
- [ ] 재발송 전 `status = 'RETRYING'`, `retry_count++` 업데이트 (PENDING → RETRYING 전이)
- [ ] 채널별 재발송 로직은 `NotificationDispatcher`의 `sendKakao` / `sendEmail` 재사용
  - 공유 메서드를 `package-private` 또는 `protected`로 노출, 또는 별도 `ChannelSender` 컴포넌트 추출
- [ ] 재발송 성공: `status=SENT`, `sent_at=now()`, `retry_count` 그대로
- [ ] 재발송 실패: `status=RETRYING`, `retry_count++`; `retry_count >= 3`이면 `status=FAILED`
- [ ] 건별 오류는 로그만 기록 — 예외 전파 금지 (한 건 실패가 다음 건 재발송을 중단하면 안 됨)
- [ ] Flyway 마이그레이션 불필요 — V6 스키마에 `retry_count`, `status`, `sent_at` 기존 존재

## 영향 범위

- **영향 레이어**: backend(notification)
- **신규 파일**:
  - `backend/.../notification/NotificationRetryJob.java`
- **수정 파일**:
  - `backend/.../notification/services/NotificationDispatcher.java` — 채널 발송 메서드 접근자 조정
  - (선택) `backend/.../notification/services/ChannelSender.java` — 채널 발송 로직 분리 (아키텍처 판단 필요)
- **DB 변경**: 없음 (V6 기존 컬럼 활용)
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- `DisclosurePollingJob` — `@Scheduled` 패턴 참고: `fixedDelay` + `@ConditionalOnProperty`로 테스트 비활성화
- `NotificationDispatcher.sendKakao/sendEmail` — 재발송 시 동일 경로 재사용
- WORKLOG 2026-06-08 Wave 2 결정: "발송 후 상태 업데이트 실패 시 sent_at IS NULL 체크로 이중 발송 방지 필요"

## 리스크 / 법적 검토

- **이중 발송 위험**: `sent_at IS NULL` 체크가 핵심 방어. PENDING 고착 시나리오: `markSent()` 호출 성공 → save() 실패 → `sent_at=NULL` 잔류 → RetryJob 재발송 → **이중 발송**. 해결: `sent_at`을 save() 전 업데이트(optimistic) 또는 DB 레벨 `sent_at IS NULL` 조건 재확인 후 발송.
- **카카오 API 비용**: 재발송 3회는 비용 발생. MAX_RETRY 상수화 + 향후 관리자 대시보드 노출 권장.
- **동시 실행 방지**: 다중 인스턴스 배포 시 중복 재발송. `@SchedulerLock`(ShedLock) 또는 DB `FOR UPDATE SKIP LOCKED` 적용 권장 (MVP: 단일 인스턴스이므로 허용).

## 권장 구현 방향

**접근법 A (권장)**: `NotificationDispatcher` 내 채널 발송 메서드를 `package-private`으로 유지하고, `NotificationRetryJob`을 동일 패키지에 배치 → 기존 메서드 재사용. 추가 클래스 최소화.

**접근법 B**: `ChannelSender` 컴포넌트 추출 → `NotificationDispatcher` + `NotificationRetryJob` 모두 주입. 관심사 분리 명확하나 1개 클래스 추가.

MVP 단계에서는 접근법 A로 빠르게 구현하고, 채널 종류 증가 시 접근법 B로 전환.

## Tech Review (dc-tech-review · 2026-06-08)

### 아키텍처 분해

- **영향 레이어**: backend(notification) 단독
- **신규 vs 수정**:
  - 신규: `Flyway V15`, `ChannelSender.java`, `NotificationRetryJob.java`
  - 수정: `NotificationEntity.java`, `NotificationDispatcher.java`, `NotificationRepository.java`

**설계 결정 — 접근법 B(ChannelSender 추출) 확정**

Spec 접근법 A(package-private 노출)를 버리고 B로 확정한다.

이유:
1. `sendKakao/sendEmail`은 현재 `private` — 동일 패키지라도 직접 호출 불가
2. RetryJob이 Dispatcher에 의존하면 순환 의존 위험 + 불필요한 책임 결합
3. ChannelSender 추출 시 Dispatcher·RetryJob 모두 동일 빈을 주입 — 발송 로직 단일 진실 소스

**설계 결정 — Flyway V15 (notifications 메시지 컬럼 추가) 필요**

현재 `NotificationEntity`에 body/subject가 없어 RetryJob이 재발송 시 Disclosure + AnalysisResult를 재조회해야 하고 cross-domain 의존이 늘어난다.
`notifications` 테이블에 `message_body TEXT`, `message_subject VARCHAR(200)` 추가 후 최초 발송 시 저장 → RetryJob은 entity에서 직접 읽어 재사용. 재조회 불필요.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `V15__add_notification_message.sql` — message_body TEXT, message_subject VARCHAR(200) 추가 | DB/Flyway | BE | 하 | - |
| 2 | `NotificationEntity` — messageBody/messageSubject 필드 추가 + `markRetrying()` 메서드 (status=RETRYING, retry_count++) | backend/notification | BE | 하 | #1 |
| 3 | `ChannelSender` 컴포넌트 추출 — sendKakao(UserEntity, NotificationEntity)/sendEmail(UserEntity, NotificationEntity) 이관. 내부에서 body/subject는 entity에서 읽음 | backend/notification/services | BE | 중 | #2 |
| 4 | `NotificationDispatcher` 수정 — ChannelSender 주입 + record 저장 시 body/subject 포함 | backend/notification/services | BE | 하 | #3 |
| 5 | `NotificationRepository` 메서드 추가 — `findRetryTargets()`: `status IN ('PENDING','RETRYING') AND sent_at IS NULL AND retry_count < 3` + `@Modifying @Query` 조건부 RETRYING 전이 메서드 | backend/notification/repositories | BE | 중 | #2 |
| 6 | `NotificationRetryJob` 구현 — `@Scheduled(fixedDelay=300_000)`, 재발송 루프, 건별 try-catch 격리, MAX_RETRY=3 상수화 | backend/notification | BE | 중 | #3 #4 #5 |
| 7 | `NotificationRetryJobIntegrationTest` — Testcontainers, FAILED 레코드 생성 → 잡 수동 호출 → SENT 확인, 3회 초과 → FAILED 확정 확인 | backend/test/notification | BE | 중 | #6 |

### DB / 마이그레이션 영향

- **V15__add_notification_message.sql** (신규 필요):
  ```sql
  ALTER TABLE notifications
      ADD COLUMN message_body    TEXT,
      ADD COLUMN message_subject VARCHAR(200);
  ```
  - `NOT NULL` 제약 없음 — 기존 PENDING 레코드가 NULL이어도 RetryJob이 skip 처리 가능
  - V6 인덱스(`idx_notifications_status` partial) 변경 없음

### 외부 계약 영향

- 카카오 알림톡/메일 클라이언트 시그니처 변경 없음
- DART/KRX API 무관

### 리스크 & 법적 검토

- **이중 발송 원자성** (High 리스크): `sent_at IS NULL` 체크만으로 부족. RetryJob은 발송 전 `UPDATE notifications SET status='RETRYING', retry_count=retry_count+1 WHERE id=? AND sent_at IS NULL AND status IN ('PENDING','RETRYING')` 조건부 UPDATE를 먼저 실행. 0건이면 다른 인스턴스가 처리 중 → skip. 1건이면 독점 확보 후 발송. NotificationRepository에 `@Modifying @Query`로 구현.
- **다중 인스턴스 (Medium 리스크)**: MVP는 단일 인스턴스이므로 ShedLock 없이 위 조건부 UPDATE로 충분. 다중 인스턴스 배포 시 ShedLock 추가 필요 — 주석으로 명시.
- **카카오 API 비용 (Low)**: 재발송 최대 3회. MAX_RETRY 상수화 + application.yml 외부화 권장(`dartcommons.notification.max-retry=3`).
- **투자 권유 표현**: RetryJob은 메시지를 재생성하지 않고 entity에 저장된 body를 그대로 재사용 — 면책 문구는 Dispatcher가 최초 저장 시 포함 완료. 추가 검토 불필요.

### 예상 wave 수

- **Wave 1** (본문): 작업 카드 #1~#6 (V15 + Entity + ChannelSender + Dispatcher 수정 + Repository + RetryJob)
- **Wave 2** (테스트): 작업 카드 #7 (NotificationRetryJobIntegrationTest)
- 총 2 waves
