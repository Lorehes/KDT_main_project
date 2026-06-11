-- V18: notifications 테이블에 읽음 처리 컬럼 추가
-- is_read: 사용자가 알림을 읽었는지 여부 (기본값 FALSE)
-- read_at: 읽음 처리 시각 (NULL = 미읽음)
-- idx_notifications_unread: 미읽음 카운트 조회 성능 최적화 (partial index)

ALTER TABLE notifications ADD COLUMN is_read BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE notifications ADD COLUMN read_at TIMESTAMPTZ;

CREATE INDEX idx_notifications_unread ON notifications (user_id, is_read) WHERE is_read = FALSE;
