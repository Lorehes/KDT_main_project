---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-11
---

# 알림 읽음 처리 Spec (백엔드 is_read + PATCH API)

> 상태: Draft → **Approved** (2026-06-11, dc-tech-review 승인)

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

## Tech Review (dc-tech-review · 2026-06-11)

### 아키텍처 분해
- **영향 레이어**: backend(`notification` 도메인, `user` 도메인 일부) + frontend(알림 관련 4파일)
- **신규**: `V18__add_is_read_to_notifications.sql`, `NotificationReadService.java` (또는 `NotificationHistoryService` 확장)
- **수정**: `NotificationEntity`, `NotificationRepository`, `NotificationResponse`, `NotificationController`, `notifications.ts`, `NotificationModal.tsx`, `notifications/page.tsx`, `TopBar.tsx`

### 작업 카드
| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `V18__add_is_read_to_notifications.sql` — `is_read BOOLEAN NOT NULL DEFAULT FALSE` + `read_at TIMESTAMPTZ` + partial index | backend/migration | 하 | - |
| 2 | `NotificationEntity` `isRead`·`readAt` 필드 + `markRead()` 메서드 추가 | backend/notification/entities | 하 | #1 |
| 3 | `NotificationRepository` — `countByUserIdAndIsReadFalse`, bulk UPDATE `markAllReadByUserId` JPQL 추가 | backend/notification/repositories | 하 | #2 |
| 4 | `NotificationResponse` — `isRead` 필드 추가, `from()` 팩토리 반영 | backend/user/dto | 하 | #2 |
| 5 | `NotificationHistoryService` — `markRead(userId, id)`, `markAllRead(userId)`, `getUnreadCount(userId)` 추가 (IDOR: userId 소유권 검증) | backend/user/services | 중 | #3 #4 |
| 6 | `NotificationController` — `PATCH /{id}/read`, `PATCH /read-all`, `GET /unread-count` 엔드포인트 추가 | backend/user/controllers | 하 | #5 |
| 7 | `notifications.ts` — `Notification` 타입 `is_read` 추가 + `useMarkAsRead`, `useMarkAllAsRead`, `useUnreadCount` 훅 | frontend/lib/api | 하 | #6 |
| 8 | `NotificationModal.tsx` — 로컬 Set → `useMarkAsRead`/`useMarkAllAsRead` mutation 교체 | frontend/components | 하 | #7 |
| 9 | `notifications/page.tsx` — 동일 교체 + `is_read` 서버 상태 반영 | frontend/app | 하 | #7 |
| 10 | `TopBar.tsx` — `useUnreadCount()` 연결, 항상 보이는 점 → 실데이터 조건부 표시 | frontend/components | 하 | #7 |

### DB / 마이그레이션 영향
- **Spec 오류 수정**: Spec에 V17로 기재됐으나 `V17__add_phone_verified_to_users.sql`이 이미 존재 → **V18** 사용
- `V18__add_is_read_to_notifications.sql`:
  ```sql
  ALTER TABLE notifications ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;
  ALTER TABLE notifications ADD COLUMN read_at TIMESTAMPTZ;
  CREATE INDEX idx_notifications_unread ON notifications (user_id, is_read) WHERE is_read = FALSE;
  ```
- `ddl-auto: validate` 통과를 위해 Entity 필드 추가 필수 (#2와 동시 적용)

### 외부 계약 영향
- 없음 (카카오/DART/LLM 무관)

### 구현 주의사항
- **IDOR 방어**: `markRead(userId, id)` — `WHERE id = ? AND user_id = ?` 조건 필수. 타인 알림 읽음 처리 차단
- **bulk UPDATE**: `markAllRead`는 `UPDATE notifications SET is_read=true, read_at=now() WHERE user_id=? AND is_read=false` 단일 쿼리 — N+1 금지
- **FE 캐시 무효화**: mutation 성공 시 `["notifications"]` + `["unread-count"]` 쿼리 invalidate
- **unread-count 폴링**: `useUnreadCount`는 `staleTime: 30_000` (30초) — WebSocket은 MVP 이후

### 리스크 & 법적 검토
- IDOR(Insecure Direct Object Reference): PATCH `/{id}/read`에서 `userId` 소유권 미검증 시 타인 알림 조작 가능 → `@AuthenticationPrincipal` + WHERE 조건 필수 (P0)
- 대량 읽음 처리 성능: bulk UPDATE로 해결, 인덱스(`idx_notifications_unread`) partial로 효율 확보
- Flyway 불변 원칙: V18 이상 신규 번호 사용, V1~V17 절대 수정 금지

### 예상 wave 수
- **Wave 1**: BE (#1~#6) — 마이그레이션 + Entity + Repository + Service + Controller
- **Wave 2**: FE (#7~#10) — API 훅 + 컴포넌트 3종 교체

