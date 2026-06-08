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

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
