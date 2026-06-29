-- disclosure-content-text-fetch Spec: DART document.xml 본문 fetch 상태 추적
-- content_fetched_at IS NOT NULL → fetch 시도 완료 (성공·영구실패 모두)
-- content_fetched_at IS NULL     → 미시도 or 일시 네트워크 오류(재시도 대상)
-- content_text IS NULL + content_fetched_at IS NOT NULL → fetch 했으나 본문 없음 (무한 재시도 방지)

ALTER TABLE disclosures
    ADD COLUMN content_fetched_at TIMESTAMPTZ;

-- 백필 타겟팅 인덱스: content_fetched_at IS NULL인 공시를 최신순 조회
-- DisclosureContentBackfillService.findPendingContentFetchIds() 에서 사용
CREATE INDEX idx_disclosures_content_pending
    ON disclosures (rcept_dt DESC)
    WHERE content_fetched_at IS NULL;
