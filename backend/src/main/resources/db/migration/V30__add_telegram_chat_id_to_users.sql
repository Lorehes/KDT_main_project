-- V30: 텔레그램 알림 채널 실발송 (telegram-notification-channel Spec 카드 #1)
-- 딥링크 /start 토큰 연동으로 서버가 취득한 텔레그램 chat_id 저장.
-- 평문 VARCHAR 저장 근거(Tech Review 확정): 금융 PII 아님, 봇 토큰 없이는 단독 악용 불가,
-- 봇 차단/토큰 revoke 이중 킬스위치 존재. API 응답에는 telegram_linked 불리언만 노출.
ALTER TABLE users
    ADD COLUMN telegram_chat_id VARCHAR(32);

COMMENT ON COLUMN users.telegram_chat_id IS
    '텔레그램 봇 1:1 대화방 ID. 딥링크 /start 토큰 연동으로만 취득(수동 입력 금지). NULL=미연동. 로그 마스킹 필수.';
