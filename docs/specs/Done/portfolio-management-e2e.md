---
type: spec
status: Done
created: 2026-06-16
updated: 2026-06-23
---

# 포트폴리오 종목 관리 E2E Spec (portfolio-management-e2e)

> 상태: Approved (2026-06-16, dc-tech-review 승인) → **Done** (2026-06-23, 구현·리뷰·커밋 완료)

## 배경 / 목적

가입 완료 후 "보유 종목 등록" 체크리스트 단계. FE-BE 연결은 완료됐으나
**`PortfolioResponse`에 `corp_name` 미포함**, **`staleTime` 미설정**,
**per-stock 알림 설정 미지원** 3건의 잔여 갭이 있다.

- **페르소나**: A(개인 투자자), C(시니어·입문 투자자)
- **BM 티어**: Free(3종목), Pro(10종목), Premium(무제한)

### 현황 (코드 기반 확인)

| 항목 | 상태 |
|------|------|
| `POST /portfolios` FE→BE 연결 | ✅ 완료 (`useCreatePortfolio`) |
| Free 3종목 422 방어 | ✅ BE 구현 + FE 에러 처리 |
| AES-256 암복호화 | ✅ BE `PortfolioService.toResponse()` 복호화 후 반환 |
| IDOR 방어 (403) | ✅ `findByIdAndUserId` 스코프 쿼리 |
| 중복 종목 409 방어 | ✅ BE + FE 에러 분기 |
| 미등록 종목코드 400 방어 | ✅ `stockRepository.existsByStockCode()` |
| `GET /portfolios` 목록 연결 | ✅ `usePortfolios()` |
| `DELETE /portfolios/{id}` 연결 | ✅ `useDeletePortfolio()` |
| `corp_name` 응답 포함 | ❌ BE `PortfolioResponse`에 미포함 — stocks JOIN 필요 |
| `staleTime` 설정 | ❌ `usePortfolios()` 미설정 — 매 마운트 재요청 |
| per-stock 알림 on/off | ❌ `notify_enabled` 폼 UI는 있으나 BE 미지원으로 전송 제외 |
| 종목 수정 (PATCH) | ❌ FE 주석에 "현재 미구현" 명시 |

---

## 요구사항

### R1 — `PortfolioResponse`에 `corp_name` 추가 (BE)

`PortfolioService.toResponse()`에서 `stockRepository.findByStockCode(e.getStockCode())`로
`Stock.corpName`을 조회해 `PortfolioResponse`에 포함.
`Stock` 조회 실패 시 `corp_name: null` 허용(방어적 처리).

- `PortfolioResponse` 레코드에 `String corpName` 필드 추가
- FE `Portfolio` 인터페이스의 `corp_name?: string` → 선택 해제 (`corp_name: string`)

> `PortfolioService`는 이미 `StockRepository`를 직접 참조(마스터 도메인 예외, CLAUDE.md §3-2) —
> 추가 의존성 없이 `stockRepository.findByStockCode()` 재사용.

### R2 — `staleTime` 설정 (FE)

`usePortfolios()` 훅에 `staleTime: 60_000` (1분) 추가.
포트폴리오 목록은 빈번하게 변경되지 않으므로 포커스 복귀 시 재요청 억제.

### R3 — per-stock `notify_enabled` BE 지원 결정

현재 `PortfolioRequest`에 `notifyEnabled` 필드가 없고 폼 UI는 전송 제외.
아래 두 방향 중 결정:

- **옵션 A**: 계정 전역 알림 설정으로 일원화 (현재 방식 유지) — `notify_enabled` 폼 UI 제거
- **옵션 B**: `portfolios.notify_enabled` 컬럼 추가 + `PortfolioRequest`에 필드 추가 (Flyway V20 필요)

> 권장: **옵션 A** — MVP에서 per-stock 알림 토글의 UX 가치가 불명확. 폼 UI에서 알림 토글을 제거하고
> 알림 설정 페이지(`/notifications/settings`)로 유도하는 안내 링크로 대체.

### R4 — 종목 수정 PATCH 구현 (선택, 우선순위 낮음)

`PUT /portfolios/{id}`는 구현됨. FE `useUpdatePortfolio()` 훅은 존재하나
`/portfolios/new?edit=true` 진입 경로 미구현.
MVP 필수 기능이 아니므로 별도 Spec 분리 가능.

---

## 영향 범위

- **영향 레이어**: backend(`user/`) + frontend(`portfolios/`, `lib/api/portfolios.ts`)
- **영향 파일**:
  - `backend/.../user/dto/PortfolioResponse.java` — `corpName` 필드 추가
  - `backend/.../user/services/PortfolioService.java` — `toResponse()`에 stocks 조회 추가
  - `frontend/src/lib/api/portfolios.ts` — `corp_name` non-optional + `staleTime`
  - `frontend/src/app/(app)/portfolios/new/page.tsx` — 알림 토글 처리(R3 결정 후)
- **DB 변경**: 옵션 A 선택 시 없음. 옵션 B 선택 시 V20 마이그레이션.
- **외부 계약**: 없음

---

## 관련 패턴 / 과거 사례

- [[mvp-missing-endpoints]] Done — 포트폴리오 CRUD 엔드포인트 구현 원본
- [[security-hardening-mvp]] Done — AES-256 암호화·IDOR·Free 제한 패턴
- [[performance-caching-staletime]] Draft — staleTime 전략 (병행 가능)
- 기존 구현: `backend/.../user/services/PortfolioService.java` (R1 패턴 확인)

## 리스크 / 법적 검토

- `avg_buy_price`·`quantity` 복호화 값은 FE 로그(`console.log`) 절대 금지 (CLAUDE.md §7 — 금융 개인정보)
- `corp_name` 추가 시 N+1 주의 — `findByUserId()` 목록 후 종목별 stockRepository 조회 발생.
  해결: `PortfolioRepository.findByUserIdWithStock()` JOIN FETCH 쿼리로 일괄 조회.

## 권장 구현 방향

R1(corp_name) → R2(staleTime) 순으로 구현. R3는 옵션 A(알림 토글 UI 제거)로 결정 후 반영.
N+1 방지를 위해 `PortfolioRepository`에 `@EntityGraph` 또는 JPQL JOIN FETCH 추가 권장.

## Tech Review (dc-tech-review · 2026-06-16)

### 아키텍처 분해

- **영향 레이어**: backend(`user/dto`, `user/services`, `stocks/repositories`) / frontend(`lib/api/portfolios.ts`, `portfolios/new/page.tsx`)
- **신규**: `StockRepository.findByStockCodeIn()`, `StockRepository.findByStockCode()`
- **수정**: `PortfolioResponse`(필드 추가), `PortfolioService.listPortfolios()`(bulk 조회), `portfolios.ts`(staleTime), `portfolios/new/page.tsx`(알림 토글 제거)

### 작업 카드

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `StockRepository`에 `findByStockCodeIn(Collection<String>)` + `findByStockCode(String)` 추가 | backend/stocks | 하 | - |
| 2 | `PortfolioResponse`에 `@JsonProperty("corp_name") String corpName` 필드 추가 | backend/user/dto | 하 | - |
| 3 | `PortfolioService.listPortfolios()` bulk 조회 리팩터 + 단건 메서드 corpName 포함 | backend/user/services | 중 | #1, #2 |
| 4 | `usePortfolios()` 훅에 `staleTime: 60_000` 추가 + FE `Portfolio.corp_name` nullable 유지 | frontend/lib/api | 하 | #2 확정 후 |
| 5 | `portfolios/new/page.tsx` 알림 토글 섹션 제거 → `/notifications/settings` 안내 링크 교체 | frontend/portfolios | 하 | - |

### 구현 메모 (#3 상세)

`listPortfolios()` 변경 방향:
```
1. portfolioRepository.findByUserId(userId) → entities
2. entities에서 stockCode 수집 → Set<String>
3. stockRepository.findByStockCodeIn(stockCodes) → Map<stockCode, corpName> 빌드
4. entities.stream().map(e -> toResponse(e, corpNameMap.get(e.getStockCode()))).toList()
```
단건 응답(`createPortfolio`, `updatePortfolio`, `getPortfolio`):
```
stockRepository.findByStockCode(e.getStockCode())
    .map(Stock::getCorpName).orElse(null)
```

### DB / 마이그레이션 영향

- **R3 옵션 A 선택 시**: 없음
- **R3 옵션 B 선택 시**: `V19__add_notify_enabled_to_portfolios.sql` (현재 최신 V18 기준)
  - Spec의 "V20" 표기는 오기 — 실제 최신 마이그레이션은 V18

### 외부 계약 영향

- 없음 (DART/KRX/카카오/LLM 변경 없음)

### 리스크 & 법적 검토

- `avg_buy_price`·`quantity` 복호화 결과는 `toResponse()` 내에서만 처리 — 절대 로그 금지 (CLAUDE.md §7)
- `PortfolioResponse` record 필드 추가 시 컴파일 타임에 `PortfolioService.toResponse()` 시그니처 전체 검토 필요 (Java record 불변 구조)
- FE `Portfolio.corp_name`은 `?: string` nullable 유지 권장 — BE stocks 테이블에 없는 stock_code 등록 엣지케이스 대비

### 예상 wave 수

- **Wave 1** (BE): 카드 #1 → #2 → #3 (순서 의존)
- **Wave 2** (FE): 카드 #4, #5 (병렬 가능)
