---
type: docs
status: active
created: 2026-06-02
updated: 2026-06-02
---

# scripts/data_collection

운영자용 일회성 데이터 수집 스크립트. 백엔드 부팅 경로에 외부 API 의존을 넣지 않기 위한 분리.

## seed_stocks.py — 종목 마스터 시드 생성

코스피200 + 코스닥150 구성종목(약 350종목)을 KRX에서 수집하고 DART `corpCode.xml`과 매핑해
Flyway 마이그레이션 SQL(`V10__seed_stocks.sql`)을 생성한다.

### 사전 준비

```bash
cd scripts/data_collection
python -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt

export DART_API_KEY=<발급받은 키>
```

### 실행

```bash
# 기본 — 오늘 기준, V10 SQL을 backend/.../db/migration/ 에 출력
python seed_stocks.py

# 특정 기준일자 (코스피200/코스닥150 리밸런싱 직후 시점 반영)
python seed_stocks.py --date 20260601

# 출력 경로 지정 (검토용으로 별도 위치에 두고 싶을 때)
python seed_stocks.py --output /tmp/V10__seed_stocks.sql
```

### 출력 SQL 형식

```sql
INSERT INTO stocks (stock_code, corp_code, corp_name, market, sector) VALUES
  ('005930', '00126380', '삼성전자', 'KOSPI', '전기·전자'),
  ...
ON CONFLICT (stock_code) DO UPDATE
SET corp_code = EXCLUDED.corp_code, ..., updated_at = now();
```

### 적용

```bash
# 1. 생성된 V10__seed_stocks.sql을 git에 커밋
git add backend/src/main/resources/db/migration/V10__seed_stocks.sql
git commit -m "feat(stocks): seed 350 stocks for KOSPI200+KOSDAQ150"

# 2. 다음 백엔드 부팅 시 Flyway가 자동 적용
./gradlew bootRun
```

### 분기 리밸런싱 (편입/제외 종목 갱신)

- **V10은 수정 금지** (Flyway 불변 원칙, CLAUDE.md §6-3)
- 분기마다 `V{n}__resync_stocks.sql` 새 마이그레이션 생성
- 또는 `StockMasterSyncJob`(분기 1회 `@Scheduled`)이 KRX/DART에서 직접 갱신
