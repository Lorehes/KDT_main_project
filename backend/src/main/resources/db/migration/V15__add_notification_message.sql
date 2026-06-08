-- V15__add_notification_message.sql
-- [목적] notifications 테이블에 message_body/message_subject 컬럼 추가 — RetryJob이 재발송 시
--        Disclosure·AnalysisResult 재조회 없이 entity에서 직접 읽어 재사용.
-- [이유] Tech Review 결정: cross-domain 의존 최소화. Dispatcher가 최초 발송 시 body/subject 저장
--        → RetryJob은 저장된 값을 그대로 재사용 (면책 문구 포함 보장).
-- [사이드 임팩트] 기존 PENDING 레코드는 NULL 잔류 → RetryJob에서 NULL 체크 후 skip 처리 필요.
--               V6 인덱스(idx_notifications_status partial) 변경 없음.
-- [수정 시 고려사항] message_body는 TEXT(무제한) — 대용량 메시지 환경 시 VARCHAR(4000) 상한 검토.
--                  message_subject는 이메일 제목 기준 200자 — 채널 확장 시 재검토.

ALTER TABLE notifications
    ADD COLUMN message_body    TEXT,
    ADD COLUMN message_subject VARCHAR(200);

COMMENT ON COLUMN notifications.message_body    IS '최초 발송 시 저장된 알림 본문. RetryJob 재발송 시 재사용.';
COMMENT ON COLUMN notifications.message_subject IS '최초 발송 시 저장된 이메일 제목. RetryJob 재발송 시 재사용.';
