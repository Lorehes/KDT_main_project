-- V7__add_notification_settings_to_users.sql
-- [목적] users에 알림 빈도·종류 필터·거래시간 외 허용 컬럼 추가 (통합기획서 §4.3, §9.2).
-- [이유] notify_channel/notify_enabled만으로는 다이제스트(일1/일2/주1)·호재악재 필터가 구현 불가.
-- [사이드 임팩트] 알림 디스패처에 빈도 분기(INSTANT vs 다이제스트) 경로 신설 필요(후속 작업).
--                기존 사용자는 기본값(INSTANT/ALL/TRUE)이 적용되어 행동 변화 없음.
-- [수정 시 고려사항] enum 변경 시 CHECK 제약과 애플리케이션 @Enumerated(STRING) 동기화.
--                  notify_type_filter는 analysis_results.sentiment(POSITIVE/NEGATIVE)와 매칭한다.

ALTER TABLE users
    ADD COLUMN notify_frequency   VARCHAR(10) NOT NULL DEFAULT 'INSTANT'
        CHECK (notify_frequency IN ('INSTANT','DAILY_1','DAILY_2','WEEKLY')),
    ADD COLUMN notify_type_filter VARCHAR(15) NOT NULL DEFAULT 'ALL'
        CHECK (notify_type_filter IN ('POSITIVE_ONLY','NEGATIVE_ONLY','ALL')),
    ADD COLUMN off_hours_allowed  BOOLEAN     NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN users.notify_frequency   IS '알림 빈도(통합기획서 §4.3). DAILY_*·WEEKLY는 다이제스트';
COMMENT ON COLUMN users.notify_type_filter IS 'analysis_results.sentiment와 접두사 정합(POSITIVE/NEGATIVE)';
COMMENT ON COLUMN users.off_hours_allowed  IS '장 마감 후·주말·공휴일 알림 허용 여부';

-- 다이제스트 배치 대상 조회 인덱스 (NOT INSTANT + 활성 사용자)
CREATE INDEX idx_users_digest_target
    ON users (notify_frequency)
    WHERE deleted_at IS NULL
      AND notify_enabled = TRUE
      AND notify_frequency <> 'INSTANT';
