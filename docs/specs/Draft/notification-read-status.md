---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# 알림 읽음 처리 Spec (백엔드 is_read + PATCH API)

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

`frontend-full-ui-implementation`(Done)에서 알림 읽음 처리는 프론트엔드 Zustand `Set<number>`로 임시 구현됐다. 새로고침·탭 이동 시 읽음 상태가 초기화된다. 이 Spec은 백엔드 영속 읽음 상태와 FE 동기화를 완성한다.

- **현황**: `notifications` 테이블에 `is_read` 컬럼 없음. PATCH API 미존재. FE는 로컬 Set으로 임시 처리.
- **목표**: 알림 읽음 상태 DB 영속 저장 + FE 즉시 반영. 미읽음 카운트 TopBar 벨에 실시간 표시.
- **BM 연관**: 전 플랜 공통 기능.

---

## 요구사항

### 백엔드

- [ ] **R1** `notifications` 테이블 `is_read` 컬럼 추가 — `V17__add_is_read_to_notifications.sql`
- [ ] **R2** `PATCH /api/v1/notifications/{id}/read` 엔드포인트 추가 — 단건 읽음 처리
- [ ] **R3** `PATCH /api/v1/notifications/read-all` 엔드포인트 추가 — 전체 읽음 처리
- [ ] **R4** `GET /api/v1/notifications` 응답에 `is_read` 필드 추가
- [ ] **R5** 미읽음 카운트 조회 — `GET /api/v1/notifications/unread-count` (TopBar 벨 뱃지용)
- [ ] **R6** `NotificationEntity` + Repository 쿼리 메서드 추가
- [ ] **R7** `NotificationController` PATCH 엔드포인트 추가

### 프론트엔드

- [ ] **R8** `notifications.ts` API 훅 업데이트 — `useMarkAsRead(id)` + `useMarkAllAsRead()` mutation 추가
- [ ] **R9** `useUnreadCount()` 훅 추가 — `GET /notifications/unread-count` 폴링(30초) 또는 캐시 무효화
- [ ] **R10** `NotificationModal` 읽음 처리 교체 — 로컬 Set → `useMarkAsRead` mutation
- [ ] **R11** `NotificationsPage` 읽음 처리 교체 — 로컬 Set → mutation
- [ ] **R12** TopBar 벨 뱃지 실데이터 — `useUnreadCount()` 결과로 교체

---

## 영향 범위

- **영향 레이어**: backend(`notification` 도메인) + frontend(알림 관련 파일 4개)
- **DB 변경**: `V17__add_is_read_to_notifications.sql` (컬럼 추가)
- **외부 계약**: 없음

### DB 마이그레이션

```sql
-- V17__add_is_read_to_notifications.sql
ALTER TABLE notifications ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMPTZ;
CREATE INDEX idx_notifications_unread ON notifications (user_id, is_read) WHERE is_read = FALSE;
```

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../notification/entities/NotificationEntity.java` | `isRead`, `readAt` 필드 추가 |
| `backend/.../notification/repositories/NotificationRepository.java` | `countByUserIdAndIsReadFalse` 등 쿼리 추가 |
| `backend/.../notification/controllers/NotificationController.java` (신규·확장) | PATCH 엔드포인트 추가 |
| `frontend/src/lib/api/notifications.ts` | `Notification` 타입에 `is_read` 추가 + mutation 훅 2종 |
| `frontend/src/components/layout/NotificationModal.tsx` | 로컬 Set → mutation 교체 |
| `frontend/src/app/(app)/notifications/page.tsx` | 동일 |
| `frontend/src/components/layout/TopBar.tsx` | `useUnreadCount()` 연결 |

---

## 관련 패턴 / 과거 사례

- `notification-dispatcher` (Done) — `NotificationEntity`, `NotificationRepository` 기존 구조
- `db_schema.md §3.6` — `notifications` 테이블 현재 구조 (`status`, `retry_count` 등)
- `api_spec.md §2.5` — 현재 알림 엔드포인트 (`GET /notifications`, `PUT /settings`)

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| 대량 읽음 처리 성능 | `read-all`은 `UPDATE ... WHERE user_id = ? AND is_read = FALSE` 단일 쿼리로 처리 |
| 미읽음 카운트 실시간성 | 30초 폴링 또는 알림 클릭 후 쿼리 무효화. WebSocket은 MVP 이후 |
| Flyway 불변 원칙 | V17 이상 신규 번호 사용. 기존 V1~V16 절대 수정 금지 |

---

## 권장 구현 방향

- `PATCH /notifications/{id}/read`: `@AuthenticationPrincipal`로 본인 알림만 수정(IDOR 방어)
- `read-all`: 트랜잭션 안에서 bulk UPDATE → 응답 204
- FE: mutation 성공 시 `["notifications"]` 쿼리 무효화 → 자동 리페치

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
