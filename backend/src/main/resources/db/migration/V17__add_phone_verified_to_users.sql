-- V17__add_phone_verified_to_users.sql
-- [목적] phone_number_enc 인증 완료 여부를 독립 컬럼으로 기록 — phone_number_enc != null과 구분.
-- [이유] 번호 등록(phone_number_enc 저장)과 인증 완료(OTP 검증)는 별개 이벤트.
--       재인증·번호 변경 시 인증 상태를 독립적으로 리셋할 수 있어야 함.
-- [사이드 임팩트] 기존 rows에 DEFAULT FALSE 적용. 알림톡 발송 가드는
--               기존 phone_number_enc != null 체크 → phone_verified = true 체크로 강화 필요(별도 Spec).
-- [수정 시 고려사항] 적용 후 불변. 번호 변경 플로우 추가 시 phone_verified를 false로 리셋하는 UPDATE 로직 필요.

ALTER TABLE users ADD COLUMN phone_verified BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN users.phone_verified IS 'phone_number_enc OTP 인증 완료 여부 — 알림톡 발송 가드(통합기획서 §11.1). 번호 변경 시 false로 리셋.';
