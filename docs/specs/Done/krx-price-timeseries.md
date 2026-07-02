---
type: spec
status: Approved
created: 2026-07-02
updated: 2026-07-02
---

# KRX 주가 시계열 수집 (stock_prices) Spec

> 상태: Draft → **Approved** (2026-07-02, dc-tech-review 승인)

## 배경 / 목적

`[[disclosure-detail-redesign]]` 예측 차트(#8/#9)는 공시 후 **D+1~D+5 일자별 주가 반응**을 요구하나, 현재 `stocks.close_price`는 **최신 종가 1개**만 보유해 일자별 반응을 계산할 수 없다. 예측 차트 구현이 이 데이터 부재로 **블록**된 상태(disclosure-detail-redesign Wave 3에서 확인).

이 Spec은 **`stock_prices` 일자별 시계열 테이블**을 신설하고 KRX 과거 주가를 백필하여, 공시 유사 사례의 실측 D+1~D+5 등락(방식 A)을 산출할 데이터 기반을 마련한다.

- 페르소나: A(바쁜 직장인)·D(데이터 중시) — "과거 비슷한 공시는 5일간 평균 이렇게 움직였다"는 실측 근거 제공.
- BM 티어: **Pro**(예측 차트는 Pro, disclosure-detail-redesign 확정). 단 본 Spec은 데이터 계층이라 티어 무관.
- **범위 경계**: 본 Spec은 **데이터 수집·저장·반응 산출 쿼리**까지. 예측 차트 UI(#8/#9 FE)는 disclosure-detail-redesign이 이 데이터를 소비하는 후속.

## 요구사항

- [ ] `stock_prices` 시계열 테이블 신설 (Flyway V27) — `(stock_code, trade_date) → close_price`
- [ ] `KrxPriceSyncJob` 일배치 확장 — 당일 종가를 `stocks.close_price`(요약용) + `stock_prices`(시계열) 병행 적재 (멱등)
- [ ] **과거 주가 백필** — 커버 종목의 과거 N년치 일별 종가를 `KrxClient` 날짜별 조회로 적재 (진행률 조회 가능한 @Async 잡)
- [ ] **반응 산출** — 공시(stock_code, rcept_dt=D0) 기준 D+1~D+5 거래일 종가 → % 등락 계산 API/서비스
- [ ] 유사 공시 집합에 대한 D+1~D+5 **평균 등락** 산출 (예측 차트 데이터) — 방식 A(실측), LLM 예측 아님(자본시장법)
- [ ] `StockPriceProvider` seam 확장 (또는 신규 메서드) — PortfolioService 등 기존 소비자 무영향

## 영향 범위 (조사 결과)

- 영향 레이어: **backend(stocks, infrastructure/krx)** 주도 · analysis(반응 결합) · disclosure(rcept_dt·stock_code 참조)
- 영향 파일:
  - 신규: `backend/.../db/migration/V27__create_stock_prices.sql`, `stocks/entities/StockPrice.java`(또는 프로젝션), `stocks/repositories/StockPriceRepository.java`, 백필 잡/서비스, 반응 산출 서비스
  - 수정: `stocks/KrxPriceSyncJob.java`(시계열 병행 INSERT), `stocks/services/StockPriceProvider.java`+`StockPriceService.java`(seam 확장), `infrastructure/krx/KrxClient.java`(과거 날짜 반복 조회 — 이미 `fetchClosePricesFromKrx(tradeDate)`/`fetchClosePricesFromGithubCache(date)` 날짜별 지원)
  - 소비(후속): `analysis/.../AnalysisQueryService`·`Stage3RagService`(유사 공시 반응 결합), FE disclosure-detail 예측 차트
- **DB 변경**: **Flyway V27 필요** — `stock_prices` 신규 테이블. `stocks` 무변경(close_price 요약 유지). 공개 시세라 평문(암호화 불필요).
- **외부 계약**: KRX OpenAPI(MDCSTAT01501 날짜별) + GitHub cache CSV 폴백 — **기존 KrxClient 재사용**, 신규 인증·엔드포인트 없음.

## 관련 패턴 / 과거 사례

- **아키텍처가 이미 예비됨**:
  - `StockPriceProvider`(seam) — 주석 명시: *"Stage 5 착수 시 stock_prices 시계열 테이블(접근법 B)로 교체 가능 — PortfolioService는 변경 없음"* (dashboard-eval-pnl Tech Review 카드 #5).
  - `KrxPriceSyncJob` 주석: *"Stage 5 착수 시 … stocks.close_price 갱신 + stock_prices INSERT 병행으로 확장."*
  - `KrxClient` 주석: *"fetchAllClosePrices()를 stock_prices 시계열 테이블 기반으로 교체 가능."*
  - `db_schema.md §388`: *"필요 시 `stock_prices` 보조 테이블 추가 검토."*
- **백필 잡 패턴 참고**: `V25 content-backfill`, `V26 embedding-backfill` — @Async + jobId + 진행률(chunksDone/Total) + 안전망. 동일 패턴으로 price-backfill 구성.
- **이상치 방어 재사용**: `KrxClient.isValidPrice`(1원 미만 차단) + `KrxPriceSyncJob` 전일 대비 ±50% — 시계열 적재에도 적용.

## 리스크 / 법적 검토

- **자본시장법 §11.1**: 예측 차트는 "과거 유사 사례 **실측** 평균 등락"으로 프레이밍(방식 A). LLM 미래 예측(방식 B) 비채택. 단정·권유 카피 금지, "예측"보다 "과거 평균" 표현 권장.
- **데이터 정합(액면분할·합병)**: KRX 종가는 **수정주가 아님(raw)**. 5일 단기 반응은 영향 작으나, 백필 기간에 분할 발생 시 D0 vs D+5 종가가 불연속 → 반응 왜곡 가능. **확인 필요**: 수정주가 보정 여부(초기엔 raw + 이상치 필터로 시작, ±50% 스킵).
- **백필 비용·커버리지 (확인 필요)**:
  - 기간: 유사 공시가 Chroma 임베딩(약 3년치 공시)에서 오므로, 반응 계산엔 **그 공시들 날짜 + 5거래일**의 종가 필요 → 약 3년 백필. ~750 거래일 × ~2000 커버 종목 ≈ **150만 행**(PostgreSQL 무리 없음).
  - 소스 신뢰도: KRX 직접 과거 조회는 LOGOUT 가능, GitHub cache CSV는 날짜별 파일 존재 여부 **확인 필요**(과거 전 거래일 커버 보장 불명).
  - 비거래일: 주말·공휴일은 행 없음 → D+N은 "N번째 **거래일**"로 정의(테이블 행 순서 기준).
- **DART 원본 불변**: 반응 수치는 KRX 실측이라 LLM 변형 대상 아님(CLAUDE.md §4 부합).

## 권장 구현 방향

**단계 분할**:

1. **테이블 + 일배치 병행 적재 (Flyway V27)**: `stock_prices(stock_code, trade_date, close_price, PK(stock_code, trade_date))`. `KrxPriceSyncJob`이 당일 종가를 `stocks` + `stock_prices` 양쪽에 멱등 upsert(`ON CONFLICT DO NOTHING/UPDATE`). → 오늘부터 시계열 축적 시작.
2. **과거 백필 잡**: 거래일 리스트를 역순 반복 → `KrxClient.fetchClosePricesFromKrx(date)`(폴백 GitHub cache) → `stock_prices` 배치 INSERT. content/embedding 백필과 동일한 @Async + 진행률 + 안전망. 커버 종목만(코스피200+코스닥150) 필터로 행 수 절감.
3. **반응 산출 서비스**: `(stock_code, D0)` → D+1~D+5 거래일 종가 조회 → `(close(D+n) - close(D0)) / close(D0) × 100`. `StockPriceProvider` seam에 `findReactionSeries(stockCode, d0, days)` 추가(기존 findLatestPrice 무영향).
4. **유사 공시 평균 결합(후속 연계)**: `Stage3RagService.findSimilar` 결과 각 공시의 반응을 산출 → D+1~D+5 평균 → disclosure-detail-redesign 예측 차트가 소비. (SimilarDisclosureItem v3에 반응 필드 재추가 검토.)

**트레이드오프**:
- 접근법 A(현 stocks.close_price 요약) 유지 + B(stock_prices 시계열) 병행 — 요약 조회는 빠른 단일 컬럼, 시계열은 반응 계산용. seam이 격리하므로 PortfolioService 등 무영향.
- 백필을 Java 잡(KrxClient 재사용) vs Python 스크립트(scripts/data_collection, FDR) — **Java 권장**(KrxClient가 이미 날짜별·폴백·이상치 방어 보유, 신규 KRX 인증 불필요). Python은 대량 과거(수년×전종목) 필요 시에만 검토.

**확인 필요 항목**:
- 백필 기간(3년 vs 그 이상) 및 커버 종목 한정 여부
- 수정주가 보정 여부(초기 raw 허용?)
- GitHub cache CSV의 과거 거래일 커버리지 실측
- SimilarDisclosureItem에 반응 필드 재추가(v3) vs 별도 반응 응답 분리

## Tech Review (dc-tech-review · 2026-07-02)

### 확정된 결정 (사용자 승인 · 2026-07-02)
1. **백필 기간·범위**: **최근 3년 · 커버 종목 한정**(코스피200+코스닥150, ~341종목 ≈ 26만 행). 유사 공시가 Chroma 임베딩(≈3년치)에서 오므로 반응 계산에 충분.
2. **수정주가**: **초기 raw(무수정)** — KRX 종가 그대로 적재. 5일 단기 반응이라 분할·합병 영향 제한적, `KrxClient` 1원 미만 + `KrxPriceSyncJob` ±50% 이상치 필터로 방어. 수정주가 보정은 정확도 이슈 발생 시 후속.
3. **거래일 소스**: **평일(MON-FRI) 캘린더 역순 반복 + 비거래일 자연 스킵**. 공휴일은 KRX/GitHub cache가 빈 응답 → 행 미생성으로 자연 처리. (B128은 과거일 목록 지원 불명이라 미채택. "기존 종가일 기반"은 콜드 백필 시 순환이라 미채택.) D+N은 테이블에 존재하는 **N번째 거래일 행** 기준.
4. **멱등 정책**: **`ON CONFLICT (stock_code, trade_date) DO NOTHING`** — 백필 재실행·일배치 중복 안전. 정정(수정주가 등)이 필요하면 그때 DO UPDATE 전환.

### 아키텍처 분해
- 영향 레이어: **backend(stocks, infrastructure/krx)** 주도 · analysis(반응 결합, 후속) · disclosure(rcept_dt·stock_code read-only)
- 신규: `V27__create_stock_prices.sql`, `StockPrice` 엔티티, `StockPriceRepository`, `PriceBackfillJob`+`PriceBackfillService`(+진행률 상태/컨트롤러), 반응 산출 서비스
- 수정: `KrxPriceSyncJob`(시계열 병행 INSERT), `StockPriceProvider`+`StockPriceService`(seam에 반응 조회 추가 — 기존 findLatestPrice 무영향), `KrxClient`(변경 최소 — 날짜별 조회 메서드 이미 존재, private→호출 가능 노출만 검토)

### 스키마 확정 (조사 기반)
- `stocks.stock_code VARCHAR(6) PRIMARY KEY` 확인 → `stock_prices.stock_code` FK 참조 가능.
- 제안 DDL:
  ```sql
  CREATE TABLE stock_prices (
      stock_code  VARCHAR(6)    NOT NULL REFERENCES stocks (stock_code),
      trade_date  DATE          NOT NULL,
      close_price NUMERIC(20,4) NOT NULL,   -- stocks.close_price와 동일 정밀도(V23)
      created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
      PRIMARY KEY (stock_code, trade_date)   -- 반응 조회(종목+날짜범위 정렬) 인덱스 겸용, 멱등 upsert 키
  );
  ```
- 별도 인덱스 불필요 — PK `(stock_code, trade_date)`가 `WHERE stock_code=? AND trade_date>=D0 ORDER BY trade_date LIMIT 6`을 커버. 공개 시세라 평문(암호화 없음).

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| **Wave A — 테이블 + 일배치 병행(오늘부터 축적 시작)** ||||||
| 1 | V27 Flyway `stock_prices` 테이블 + db_schema.md 갱신 | backend/stocks | BE | 하 | - |
| 2 | `StockPrice` 엔티티 + `StockPriceRepository`(upsert·범위조회) | backend/stocks | BE | 하 | 1 |
| 3 | `KrxPriceSyncJob` 확장 — 당일 종가 stock_prices 멱등 upsert(ON CONFLICT), 이상치 필터 재사용 | backend/stocks | BE | 중 | 2 |
| **Wave B — 과거 백필** ||||||
| 4 | `PriceBackfillService`(@Async, jobId, 진행률, 안전망) — 평일 캘린더 3년 역순 반복, 비거래일 빈응답 스킵, 커버 종목 한정 | backend/stocks,infra | BE | 상 | 2 |
| 5 | `KrxClient` 날짜별 종가 반복 호출 — 커버 종목 필터, GitHub cache 폴백, 배치 INSERT | backend/infra | BE | 중 | 4 |
| 6 | 백필 컨트롤러(start/진행률) + Testcontainers 통합 테스트 | backend/stocks | BE | 중 | 5 |
| **Wave C — 반응 산출(예측 차트 데이터)** ||||||
| 7 | `StockPriceProvider.findReactionSeries(stockCode, d0, days)` seam 확장 + `StockPriceService` 구현(D+N=N번째 거래일) | backend/stocks | BE | 중 | 2 |
| 8 | 유사 공시 D+1~D+5 **평균 등락** 산출 서비스(analysis, stocks read-only) — 방식 A 실측 | backend/analysis | BE | 상 | 7 |
| 9 | (disclosure-detail-redesign 연계) SimilarDisclosureItem v3 반응 필드 or 별도 반응 응답 → FE 예측 차트 소비 | backend/analysis, frontend | BE·FE | 중 | 8 |

### DB / 마이그레이션 영향
- **Flyway V27 필요**: `backend/src/main/resources/db/migration/V27__create_stock_prices.sql`(신규 테이블). `stocks` 무변경. `ddl-auto: validate` 유지.
- 행 수 추정: 커버 종목(~341) × 3년(~750 거래일) ≈ **26만 행**(전종목 아닌 커버 한정 시). 전종목 백필 시 ~150만 행 — 커버 필터 권장.
- 멱등: `INSERT ... ON CONFLICT (stock_code, trade_date) DO NOTHING`(백필 재실행 안전) 또는 `DO UPDATE`(정정 반영). **확인 필요**: DO NOTHING vs UPDATE.

### 외부 계약 영향
- **KRX OpenAPI/GitHub cache — 기존 KrxClient 재사용**, 신규 인증·엔드포인트 없음. `fetchClosePricesFromKrx(tradeDate)`·`fetchClosePricesFromGithubCache(date)`가 이미 날짜별. 백필용 반복 호출 시 **rate limit·차단 리스크**(확인 필요 — KRX 과거일 LOGOUT 빈도, GitHub cache 과거 커버리지).
- 자체 REST: 백필 잡 컨트롤러(admin 가드) + (후속) 반응 결합 응답.

### 리스크 & 법적 검토
- **자본시장법 §11.1**: 예측 차트는 "과거 유사 사례 **실측** 평균 등락" 프레이밍 필수(방식 A). LLM 미래 예측(방식 B) 비채택 확정. 단정·권유 카피 금지.
- **수정주가(raw) 왜곡**: KRX 종가는 무수정 → 백필 기간 내 액면분할·합병 시 D0 vs D+5 불연속. 5일 단기라 영향 제한적이나 ±50% 이상치 필터로 1차 방어. **확인 필요**: 수정주가 보정 도입 여부(초기 raw 권장).
- **백필 비용·신뢰도**: 대량 과거 호출 — @Async + 진행률 + 안전망(N건 연속 실패 시 중단)로 content/embedding 백필과 동일하게 방어. 커버 종목 한정으로 행 수·호출 절감.
- **KRX 비공식 API**: 응답 필드 변경 리스크(기존 KrxClient가 이미 감내 — 빈 Map 폴백). 백필 실패는 재실행 멱등으로 복구.

### 예상 wave 수
- **3 wave / 3 PR**. Wave A(테이블+일배치)만으로도 **오늘부터 시계열 축적 시작** — 독립 가치. Wave B(백필)는 과거 데이터, Wave C(반응)가 예측 차트를 언블록. Wave C 완료 후 disclosure-detail-redesign #8/#9 재개.

<!-- 다음: 확인 필요 4건(백필 기간·수정주가·거래일 소스·멱등 정책) 결정 → /dc-spec-move Approved → /dc-implement -->
