-- V22__add_profile_fields_to_users.sql
-- [목적] users에 투자 경험·주 사용 시점 컬럼 추가 — 공시 해석 복잡도 조정 개인화 데이터 (통합기획서 §4.3).
-- [이유] V7 알림 설정 패턴과 동일하나, 두 컬럼 모두 선택 사항(nullable) — 기존 가입자는 null 유지.
--       NOT NULL DEFAULT 없음: 신규 가입자도 profile 단계 스킵 가능, 미입력 시 FE 기본값(INTERMEDIATE·REALTIME) 표시.
-- [사이드 임팩트] ddl-auto: validate → 이 마이그레이션 선적용 전 애플리케이션 기동 시 컬럼 불일치 오류.
--                UserEntity에 enum 필드 추가 후 반드시 이 마이그레이션을 먼저 적용할 것.
-- [수정 시 고려사항] 값 추가 시 CHECK 제약과 애플리케이션 enum 동기화 필수.
--                  롤백이 필요하면 V23으로 DROP COLUMN (Flyway 불변 원칙 — 이 파일 수정 금지).
--                  investment_experience는 해석 복잡도 조정 전용 — 투자 권유 판단에 활용 금지 (통합기획서 §11.1).

ALTER TABLE users
    ADD COLUMN investment_experience VARCHAR(15)
        CHECK (investment_experience IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    ADD COLUMN preferred_time VARCHAR(10)
        CHECK (preferred_time IN ('REALTIME', 'LUNCH', 'EVENING'));

COMMENT ON COLUMN users.investment_experience IS '투자 경험 수준 — 공시 해석 복잡도 조정 전용 (투자 권유 금지, 통합기획서 §11.1)';
COMMENT ON COLUMN users.preferred_time        IS '주 사용 시점 — 알림 타이밍 개인화 참고값';
