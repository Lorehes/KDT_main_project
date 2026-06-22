---
type: spec
status: Draft
created: 2026-06-22
updated: 2026-06-22
---

# 대시보드 평가 손익(포트폴리오 수익률) Spec (dashboard-eval-pnl)

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

대시보드 상단 통계 카드에 **평가 손익(보유 종목 합산 수익률)** 카드를 추가한다.
현재 FE는 이 카드를 **placeholder**(`값 "—"`, 회색 캡션 `"DB 연동 필요"`)로 선구현한 상태이며
([[dashboard-real-data]] 후속), 실데이터를 채우려면 **KRX 현재가**가 필요하다.

- **문제**: `portfolios`에 사용자가 입력한 매수가(`avg_buy_price`)·수량(`quantity`)은 있으나,
  **현재가(시세)가 어디에도 저장돼 있지 않다** → 평가금액·수익률 계산 불가.
- **페르소나**: A(적극 투자자)·C(시니어)·E(입문) — 내 보유 종목의 손익을 한눈에 확인.
- **BM 티어**: Free/Pro/Premium 공통(보유 종목 수만 티어 차등). 평가 손익은 전 티어 노출.

### 현황 (코드 기반 확인)

| 항목 | 상태 |
|------|------|
| FE 평가 손익 카드 (placeholder) | ✅ `StatCard value="—" muted note="DB 연동 필요"` 구현 |
| `portfolios.avg_buy_price_enc` · `quantity_enc` (AES-256-GCM) | ✅ V3, `PortfolioService`에서 앱 계층 복호화 |
| `KrxClient` | ⚠️ 존재하나 **시장/섹터(MKT_NM·IDX_IND_NM)만 파싱** — 현재가 미수집, "검증 미완료" |
| `stocks` 테이블 현재가 컬럼 | ❌ 없음 (price 컬럼/테이블 부재) |
| 주가 일 1회 배치 잡 | ❌ 없음 (`StockMasterSyncJob`은 **분기 1회** 마스터 갱신 전용) |
| 포트폴리오 손익 집계 API | ❌ 없음 (FE는 공시 통계만 클라이언트 집계) |
| `PortfolioResponse` 손익 필드 | ❌ 없음 (주석에 "주가 API 데이터 필요 — 향후 확장" 예고) |

> 참고: `docs/개발명세서/db_schema.md` §주석 — *"주가 시계열을 별도 테이블/외부 조회로 두고
> 핵심 5테이블에 포함하지 않았다(필요 시 `stock_prices` 보조 테이블 추가 검토)."*

---

## 요구사항

### R1 — 현재가 저장소 (BE / DB)
- 보유 종목의 **최신 종가(현재가)** 를 저장할 영속 위치 신설 (Flyway 마이그레이션, 다음 버전 `V21`).
- 저장 단위: `stock_code` → `close_price`(BigDecimal/NUMERIC) + `price_asof`(거래일/타임스탬프).
- 접근법 A/B는 **권장 구현 방향** 참조. (현재가는 **금융 개인정보 아님** — 공개 시세 → 평문 저장 가능)

### R2 — KRX 현재가 수집 (BE / infrastructure)
- `KrxClient`에 **전종목 종가 조회** 메서드 추가 (기존 `MDCSTAT01901` 응답에 `TDD_CLSPRC` 종가 필드 포함 여부 **실측 필요** — 없으면 시세 전용 BLD 별도 확인).
- KRX 비공식 인터페이스 — 기존 클라이언트와 동일하게 `@Retryable` + 실패 시 빈 결과 우아한 무시 + `SecretMasker` 로깅.
- **실측 의존**: 기존 `stocks-master-seed` 카드 #1과 동일한 KRX 응답 검증 선행 필요.

### R3 — 주가 일 1회 배치 잡 (BE)
- **장 마감 후 일 1회**(예: 평일 18:00 KST) 보유/커버 종목 현재가를 KRX에서 동기화 (CLAUDE.md §4 "주가: 일 1회 배치").
- `StockMasterSyncJob`(분기)과 **별개의 신규 잡** — `stocks` 도메인에 추가.
- 외부 호출 타임아웃·지수 백오프·예외 비중단(다음 윈도우 재시도). 단일 인스턴스 가정(멀티 인스턴스 ShedLock은 후속).
- 비거래일/장 시작 전: 직전 종가 유지(asof로 노출).

### R4 — 포트폴리오 손익 집계 API (BE)
- 신규 엔드포인트 `GET /api/v1/portfolios/summary` (JWT 인증, userId 스코프).
- 서비스 계층에서 **매수가·수량 복호화 후** 종목별 현재가와 결합해 집계:
  - `total_cost_basis = Σ(avg_buy_price × quantity)`
  - `total_eval_amount = Σ(close_price × quantity)`
  - `total_pnl = total_eval_amount − total_cost_basis`
  - `pnl_rate = total_pnl / total_cost_basis` (cost_basis 0이면 null — 0 나눗셈 방지)
- 응답 메타: `priced_count`(현재가 보유 종목 수)·`unpriced_count`·`as_of`(시세 기준일).
- **복호화 값 절대 로깅 금지**(CLAUDE.md §7). 응답에도 종목별 매수가 재노출 불필요 — 합산값만.

### R5 — FE 실데이터 연결 (FE)
- `frontend/src/lib/api/portfolios.ts`에 `usePortfolioSummary()` 훅 추가 (TanStack Query, `staleTime` 적용).
- `dashboard/page.tsx`의 평가 손익 카드 placeholder를 실데이터로 교체:
  - 정상: `+3.4%` 형태 (부호 + 색상). **한국식 색상**: 이익=빨강(`sentiment-positive`), 손실=파랑(`sentiment-negative`).
  - **접근성(WCAG 6-5)**: 색 단독 금지 → `+`/`−` 부호·`▲`/`▼` 아이콘 병기.
  - 데이터 없음(현재가 미수집/매수가 미입력): placeholder 유지 또는 `"—"` + 안내 캡션.
- preview(목업) 페이지의 `+3.4%`는 정적 유지(가입 전 체험용).

### R6 — 엣지 케이스 처리 (BE/FE)
- 매수가 또는 수량이 **null**(선택 입력) → 해당 종목은 평가 손익 집계에서 제외 + `unpriced_count` 반영.
- 종목 현재가 **미수집/구버전**(주말·공휴일·배치 실패) → 직전 종가 + `as_of` 노출, 신선도 안내.
- 보유 종목 0개 → 카드 `"—"` (Empty state와 일관).

---

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(`stocks`·`user`·`infrastructure/krx`) + frontend(`dashboard`·`lib/api`)
- **영향 파일**:
  - `backend/src/main/resources/db/migration/V21__*.sql` — 현재가 저장 컬럼/테이블 (신규)
  - `backend/.../infrastructure/krx/KrxClient.java` — 종가 조회 메서드 추가
  - `backend/.../stocks/entities/Stock.java` (접근법 A) 또는 신규 `StockPrice` 엔티티 (접근법 B)
  - `backend/.../stocks/` — 신규 `StockPriceSyncJob`(일 1회) + 서비스
  - `backend/.../user/services/PortfolioService.java` — 손익 집계 메서드 (복호화 후 계산)
  - `backend/.../user/controllers/PortfolioController.java` — `GET /portfolios/summary`
  - `backend/.../user/dto/PortfolioSummaryResponse.java` — 신규 집계 DTO
  - `frontend/src/lib/api/portfolios.ts` — `usePortfolioSummary()` 훅
  - `frontend/src/app/(app)/dashboard/page.tsx` — 평가 손익 카드 실데이터 교체
  - `frontend/src/components/domain/StatCards.tsx` — (필요 시) 부호/색상/아이콘 variant
- **DB 변경**: ✅ **필요** — Flyway `V21` (현재가 저장). `ddl-auto: validate` 유지.
- **외부 계약**: ✅ KRX OpenAPI 종가 응답 파싱 (실측 필요), 자체 REST `GET /portfolios/summary` 신규.

---

## 관련 패턴 / 과거 사례

- [[dashboard-real-data]] Draft — 대시보드 실데이터 연동 (본 Spec의 직접 선행)
- [[portfolio-management-e2e]] — 포트폴리오 CRUD + AES-256 암복호 + IDOR (집계는 동일 복호화 패턴 재사용)
- 기존 구현 참고:
  - `backend/.../infrastructure/krx/KrxClient.java` — KRX 호출/파싱/`@Retryable` 패턴
  - `backend/.../stocks/StockMasterSyncJob.java` — `@Scheduled` cron 잡 패턴(신규 일배치 잡의 템플릿)
  - `backend/.../user/services/PortfolioService.java` — 복호화 + bulk `findByStockCodeIn` N+1 회피
- Step 0(learnings): `docs/solutions` 미존재 — 과거 사례 없음(검색 생략, 흐름 비중단).

## 리스크 / 법적 검토

- **자본시장법(통합기획서 §11.1)**: 평가 손익은 *사용자 본인 입력 기반 단순 계산값* — 투자 권유 아님.
  단, 카드/문구에 "수익 보장"·"매수 추천" 등 표현 금지. 손익은 **사실 표시**에 한정.
- **금융 개인정보(CLAUDE.md §7)**: 매수가·수량 복호화 값 **평문 로깅·외부 전송 절대 금지**.
  집계 응답은 **합산값만** 반환(종목별 매수가 재노출 불필요).
- **현재가 정확성**: KRX 비공식 인터페이스 — 응답 포맷 변동/지연 가능. `as_of`로 신선도 명시, 실시간 아님 안내.
- **0 나눗셈**: `total_cost_basis = 0`(수량/매수가 미입력) 시 `pnl_rate = null` 처리.

## 권장 구현 방향

**접근법 A (권장, MVP) — `stocks` 테이블에 현재가 컬럼 추가**
- `V21__add_price_to_stocks.sql`: `close_price NUMERIC`, `price_asof DATE` 컬럼 추가.
- 일 1회 배치가 종목별 최신 종가로 **덮어쓰기**(이력 미보관).
- 장점: 단일 테이블 JOIN, 최소 변경. 단점: 마스터(안정) 테이블에 변동 데이터 혼입(원칙상 약점).

**접근법 B (확장) — 신규 `stock_prices` 시계열 테이블**
- `(stock_code, trade_date, close_price, ...)` — db_schema가 이미 후보로 언급.
- 장점: **Stage 5 RAG "주가 5일 반응"** (feature_structure)에 재사용 가능, 이력 보관.
- 단점: MVP 대비 과설계. 일 1회 INSERT + 조회 시 최신 행 선택 로직 필요.

> **결론**: 대시보드 카드 1개를 위한 MVP 범위로는 **접근법 A**. 단, Stage 5(재무/업황 5일 주가 반응)
> 착수 시점에 **접근법 B로 승격**하는 것을 전제로 `PortfolioService` 집계 로직을 시세 조회 인터페이스
> (예: `StockPriceProvider`) 뒤에 두어 저장소 교체에 영향받지 않게 설계.

구현 순서: R2(KRX 종가 실측) → R1(V21) → R3(일배치 잡) → R4(집계 API) → R5/R6(FE 연결).
**R2 실측이 전체 선행 차단점** — KRX 종가 응답 검증 실패 시 대안(공공데이터포털 시세 API 등) 비교 필요.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
