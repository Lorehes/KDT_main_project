-- V16__alter_feedbacks_reason_length.sql
-- [목적] feedbacks.reason TEXT → VARCHAR(2000) 제한 — 대량 문자열 삽입(DoS/디스크 포화) 방어(security-hardening-mvp R12).
-- [이유] TEXT는 길이 무제한 — FeedbackRequest.@Size(max=2000)로 요청 레이어 제한이 있지만 DB 레벨 이중 방어 필요.
--       앱 계층에서도 FeedbackEntity.update()에 2000자 truncate 로직 추가(동반 변경).
-- [사이드 임팩트] 기존 데이터 중 2000자 초과 reason이 있으면 ALTER 실패 → UPDATE로 사전 truncate 후 ALTER.
--               ALTER COLUMN TYPE은 전체 테이블 rewrite — MVP 단계이므로 데이터 없음(영향 없음).
-- [수정 시 고려사항] 운영 데이터가 있을 경우 배포 전 pg_dump 백업 필수.
--                  2000자 한도 조정 시 FeedbackEntity.update() + FeedbackRequest.@Size + 본 마이그레이션 동시 갱신.

-- 사전 truncate: 기존 2000자 초과 데이터가 있으면 잘라냄 (MVP 단계에서는 해당 없음)
UPDATE feedbacks SET reason = LEFT(reason, 2000) WHERE reason IS NOT NULL AND LENGTH(reason) > 2000;

-- TEXT → VARCHAR(2000) 변환
ALTER TABLE feedbacks ALTER COLUMN reason TYPE VARCHAR(2000);
