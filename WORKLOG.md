---
type: worklog
status: active
created: 2026-06-02
updated: 2026-06-02
---

# WORKLOG

> 세션 단위 작업 기록. dc-push가 자동 갱신. dc-handoff의 데이터 소스.

---

## 2026-06-02 | stocks 도메인 도입 + disclosure N+1 해소

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 2 핵심)

### 완료
- `stocks` 도메인 신규 도입 (`Stock` 엔티티 V2 매핑, `StockRepository`)
- `StockRepository.findAllStockCodes()` — 배치당 1회 Set 로드로 deferred HIGH N+1 해소
- `KrxApiProperties` 스켈레톤 + `application.yml` `dartcommons.krx.*`
- `DisclosureCollectionService` — JdbcTemplate 제거, StockRepository 의존, `collectSingle` 시그니처 변경(coveredCodes Set 파라미터)
- `DisclosureCollectionIntegrationTest` — fixture를 `stockRepository.save()`로 전환 (6/6 통과)
- `review-findings-deferred.md` — N+1 항목 제거

### 결정 (코드에 드러나지 않는 사항)
- **아키텍처 예외 (CLAUDE.md §3-2)**: 마스터 데이터 도메인(`stocks/`)은 다른 도메인이 read-only로 직접 의존 허용. write는 마스터 도메인 내부 한정. 옵션 B(shared facade)는 indirection만 늘려 기각.
- **market 필드**: String 유지 (enum 미사용). V10 시드/SyncJob과 동시 도입 예정.
- **KRX 키 발급 완료**, 출처: KRX 공개 CSV (data.krx.co.kr)

### 미완료 (다음 세션)
stocks-master-seed Wave 1 + 3년치 백필 가능 상태:
- `#1` KRX 실측 (엔드포인트/파라미터 확정) + `api_spec.md` §3.2 갱신
- `#3` `DartCorpCodeClient` (corpCode.xml zip 다운로드 + 파싱)
- `#4` `KrxClient` (RestClient + 종목 기본정보)
- `#5` `scripts/data_collection/seed_stocks.py` (KRX 공개 CSV + DART 매핑 → V10 SQL 출력)
- `#7` `V10__seed_stocks.sql` (스크립트 결과 → 적용)
- `#8` `StockMasterService` (upsert 오케스트레이터)
- `#9` `StockMasterSyncJob` (@Scheduled 분기 1회)
- **백필 진입점**: `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill` (3년치 수집 가능해짐)
- **DART 윈도우 청크 분할**: 3개월 단위 + 속도 제어
- **DB 청크 적재 최적화**: 건별 save → saveAll 배치

### deferred 잔여 (review-findings-deferred.md)
- HIGH: `Thread.sleep` 블로킹 재시도 (spring-retry 의존성 추가 결정 후)
- HIGH: `disclosure` → `infrastructure` DTO 직접 의존 (아키텍처 리팩 Spec)
- MEDIUM: `lastPolledDate` 인메모리 (멀티 인스턴스 전환 시점)
- MEDIUM: SSRF 화이트리스트 부재
- LOW: API 키 에러 로그 노출
