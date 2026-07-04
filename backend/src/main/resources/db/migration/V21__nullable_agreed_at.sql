-- V21__nullable_agreed_at.sql
-- [목적] users.terms_agreed_at / users.privacy_agreed_at NOT NULL 제약 해제.
--        OAuth 가입 계정은 동의 SSOT를 consent_logs 테이블로 일원화 — users 컬럼에 중복 기록하지 않는다.
-- [이유] autoSignup()에서 계정 생성 시점(동의 전)에 now()를 기록하면 실제 동의 시각(consent_logs)과 불일치(E3 이슈).
--        NOT NULL 제약이 있어 단순 제거가 불가능했으므로 마이그레이션으로 nullable 전환.
--        이메일 가입(signup())은 동의와 동시 발생 → 기존 데이터 및 신규 행 모두 not-null 유지 (영향 없음).
-- [사이드 임팩트] 기존 데이터는 전부 not-null — 이 마이그레이션은 신규 OAuth 계정만 null 허용.
--               JPA @Column(nullable = true) 변경과 동시 배포 필수(ddl-auto: validate 정합).
--               UserMeResponse.terms_agreed_at / privacy_agreed_at은 이미 FE에서 optional(?타입)으로 선언됨 — FE 변경 불필요.
-- [수정 시 고려사항] 되돌릴 경우: ALTER TABLE users ALTER COLUMN terms_agreed_at SET NOT NULL 이나,
--                  기존 OAuth 계정 null 행이 있으면 NOT NULL 복원 불가 — 정책 변경 시 신중히 검토.
--                  적용된 마이그레이션 파일 수정 금지(Flyway 불변 원칙, CLAUDE.md §6-3).

ALTER TABLE users ALTER COLUMN terms_agreed_at DROP NOT NULL;
ALTER TABLE users ALTER COLUMN privacy_agreed_at DROP NOT NULL;

COMMENT ON COLUMN users.terms_agreed_at IS '이메일 가입 시 동의 시각. OAuth 가입은 consent_logs가 SSOT — NULL 허용(V21)';
COMMENT ON COLUMN users.privacy_agreed_at IS '이메일 가입 시 동의 시각. OAuth 가입은 consent_logs가 SSOT — NULL 허용(V21)';
