-- V11__create_system_configs.sql
-- [목적] 시스템 설정/상태 key-value 저장소 — DisclosurePollingJob.lastPolledDate 등 영속화.
-- [이유] @Scheduled 잡의 상태가 인메모리(AtomicReference)에 있어 재기동 시 누락 가능.
--       deferred MEDIUM 해결: 재기동 후에도 직전 폴링 윈도우 이어서.
-- [사이드 임팩트] 단일 인스턴스 가정. 멀티 인스턴스 전환 시 ShedLock + 본 테이블 row-lock 동시 사용 권장.
--               key는 명시적 enum 미사용 — 단순 문자열로 자유도 확보(다른 잡도 같은 표 사용).
-- [수정 시 고려사항] 적용 후 불변. 추가 컬럼은 새 V{n} 마이그레이션.

CREATE TABLE system_configs (
    config_key  VARCHAR(100) PRIMARY KEY,
    config_value VARCHAR(500) NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 사전 정의 키 — 코드와 컨벤션 정렬:
-- 'disclosure.lastPolledDate' : DisclosurePollingJob 마지막 성공 폴링 종료일자(YYYY-MM-DD)
