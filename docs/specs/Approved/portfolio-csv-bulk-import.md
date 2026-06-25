---
type: spec
status: Approved
created: 2026-06-25
updated: 2026-06-25
---

# 포트폴리오 CSV 일괄 등록 Spec

> 상태: Draft → **Approved** (2026-06-25, dc-tech-review 승인)

## 배경 / 목적

증권사 거래내역 CSV 파일에서 보유 종목코드를 추출해 포트폴리오에 일괄 등록하는 기능.
FE의 4단계 CSV 업로드 UI(`idle → parsing → review → registering`)는 이미 완성되어 있으나,
현재 `handleBulkRegister`가 `POST /api/v1/portfolios`를 **N번 개별 호출(루프)** 하는 구현이다.

이 Spec은:
1. BE에 **`POST /api/v1/portfolios/import`** 벌크 엔드포인트를 신설하고
2. FE의 N번 루프를 **단일 API 호출**로 교체한다.

관련 티어: 전 티어 공통. 단 Free 티어는 3종목 한도 강제 (현재 단건과 동일).

## 요구사항

### BE

- [ ] **R1** `POST /api/v1/portfolios/import` 엔드포인트 신설
  - Request: `{ "stock_codes": ["005930", "000660", ...] }` (최대 50개)
  - Response (HTTP 200): `{ "added": [...], "skipped_duplicate": [...], "skipped_unsupported": [...], "skipped_limit": [...] }`
- [ ] **R2** 요청 코드별 처리 순서: ① 마스터 존재 확인 → ② 중복 확인 → ③ Free 한도 확인 → ④ 저장
- [ ] **R3** Free 티어 3종목 한도는 트랜잭션 내 원자적 적용 — `countByUserId` 후 슬롯 계산, 초과 코드는 `skipped_limit`에 분류
- [ ] **R4** 코드 목록은 상태별 편입(분류) 후 유효 코드만 `saveAll()` 단일 트랜잭션으로 일괄 저장
- [ ] **R5** 저장 성공 후 `portfolioStockCodes` 캐시 evict (`@CacheEvict(value="portfolioStockCodes", key="#userId")`)
- [ ] **R6** `stock_codes` 빈 배열이면 HTTP 400; 50개 초과이면 HTTP 400
- [ ] **R7** 요청이 전부 `skipped_*`(저장 대상 없음)이어도 HTTP 200 정상 응답 (FE가 토스트 메시지 구성)

### FE

- [ ] **R8** `frontend/src/lib/api/portfolios.ts`에 `importPortfolios(stockCodes: string[])` 함수 추가
  - `POST /api/v1/portfolios/import` 단일 호출, 응답 타입 `ImportPortfoliosResult` 정의
- [ ] **R9** `portfolios/new/page.tsx` — `handleBulkRegister`의 for 루프 제거, `importPortfolios()` 단일 호출로 교체
- [ ] **R10** 응답의 `added/skipped_duplicate/skipped_unsupported/skipped_limit` 길이를 기존 토스트 메시지 형식에 매핑 (현행 `parts[]` 로직 재사용)
- [ ] **R11** 기존 FE pre-filtering(중복 제거·체크박스 선택)은 유지 — BE 응답은 추가 분류 소스로만 사용 (FE 체크박스 선택이 우선)
- [ ] **R12** `POST /api/v1/portfolios/import` 422 응답 시("Free 한도 초과" 경쟁 조건) — `skipped_limit`으로 처리 (기존 루프의 break 패턴과 동일 의미)

## 영향 범위

### Backend

- `backend/src/main/java/com/dartcommons/user/services/PortfolioService.java` — `bulkImport()` 메서드 추가
- `backend/src/main/java/com/dartcommons/user/controllers/PortfolioController.java` — `POST /import` 엔드포인트 추가
- `backend/src/main/java/com/dartcommons/user/dto/` — `BulkImportRequest.java`, `BulkImportResult.java` (record) 추가

### Frontend

- `frontend/src/lib/api/portfolios.ts` — `importPortfolios()` 함수 + `ImportPortfoliosResult` 타입 추가
- `frontend/src/app/(app)/portfolios/new/page.tsx` — `handleBulkRegister` 루프 제거, 단일 호출로 교체

### DB / 외부 계약

- DB 변경: **없음** (Flyway 마이그레이션 불필요)
- 외부 API 변경: **없음**
- `avg_buy_price_enc`, `quantity_enc`: 벌크 임포트에서 **null 고정** (CSV에서 수량·단가 미추출, CLAUDE.md §7 금융 PII 보호)

## 관련 패턴

- 기존 단건 등록: `PortfolioService.createPortfolio()` — Free 한도 체크(`countByUserId ≥ 3`) + 중복 체크 + 마스터 존재 확인 + `@CacheEvict` 패턴 그대로 준용
- FE CSV 파싱: `frontend/src/lib/csv/parsePortfolioCsv.ts` — 종목코드 추출만, 수량·단가 미추출 (현행 유지)
- FE 기존 per-code 결과 분류: `portfolios/new/page.tsx` `handleBulkRegister` — 루프 내 `added/skippedDuplicate/skippedUnsupported/skippedLimit/skippedFailed` 분류 로직 → 단일 호출 응답 필드에 매핑
- `portfolioStockCodes` 캐시: `UserStockCodesProviderImpl` — create/delete 시 evict, bulk import도 동일 evict 필요

## 리스크

- **경쟁 조건(Free 한도)**: FE pre-filtering 후 BE 도달 전 다른 탭에서 수동 등록 시 한도 초과 가능. BE의 트랜잭션 내 원자적 count 재검사(R3)로 방지.
- **N개 일괄 저장 트랜잭션**: 50개 코드 일괄 `saveAll()` — 단건 대비 락 보유 시간 증가. MVP 단계에서 최대 50개 제한(R6)으로 수용.
- **stockMasterService 호출**: 현재 단건 등록은 1코드당 1 DB 쿼리. 벌크에서 N번 조회 → `findAllByStockCodeIn()` 일괄 조회로 최적화 가능 (구현에서 판단).
- **`skippedFailed` 범주 消滅**: 기존 루프에는 네트워크 오류용 `skippedFailed`가 있었으나 단일 호출 후에는 BE 응답 파싱으로 대체 — 응답이 완전 실패(5xx)면 전체 toast.error 처리.

## 권장 구현 방향

### BE: PortfolioService.bulkImport()

```java
@Transactional
@CacheEvict(value = "portfolioStockCodes", key = "#userId")
public BulkImportResult bulkImport(Long userId, List<String> stockCodes, Tier tier) {
    // 1. 마스터 일괄 조회 (N+1 방지)
    Map<String, String> masterMap = stockMasterService.findAllByCodes(stockCodes)
        .stream().collect(toMap(Stock::getStockCode, Stock::getCorpName));
    
    // 2. 기존 포트폴리오 코드 셋
    Set<String> existing = portfolioRepository.findStockCodesByUserId(userId);
    
    // 3. Free 슬롯 계산
    int currentCount = existing.size();
    int remainingSlots = (tier == Tier.FREE) ? Math.max(0, FREE_TIER_LIMIT - currentCount) : Integer.MAX_VALUE;
    
    // 4. 코드별 분류
    List<String> toAdd = new ArrayList<>(), dupList = new ArrayList<>(),
                  unsupported = new ArrayList<>(), limitList = new ArrayList<>();
    int addedCount = 0;
    for (String code : stockCodes) {
        if (!masterMap.containsKey(code))  { unsupported.add(code); continue; }
        if (existing.contains(code))       { dupList.add(code); continue; }
        if (addedCount >= remainingSlots)  { limitList.add(code); continue; }
        toAdd.add(code); addedCount++;
    }
    
    // 5. 일괄 저장
    List<PortfolioEntity> entities = toAdd.stream()
        .map(code -> PortfolioEntity.builder().userId(userId).stockCode(code).build())
        .toList();
    portfolioRepository.saveAll(entities);
    
    return new BulkImportResult(toAdd, dupList, unsupported, limitList);
}
```

### FE: importPortfolios + handleBulkRegister 교체

```typescript
// portfolios.ts
export interface ImportPortfoliosResult {
  added: string[];
  skipped_duplicate: string[];
  skipped_unsupported: string[];
  skipped_limit: string[];
}

export async function importPortfolios(stockCodes: string[]): Promise<ImportPortfoliosResult> {
  return apiClient<ImportPortfoliosResult>("/portfolios/import", {
    method: "POST",
    body: JSON.stringify({ stock_codes: stockCodes }),
  });
}
```

```typescript
// portfolios/new/page.tsx — handleBulkRegister 교체
const handleBulkRegister = useCallback(async () => {
  const toRegister = csvItems.filter(i => !i.isDuplicate && i.checked).map(i => i.code);
  if (!toRegister.length) { resetCsvState(); return; }
  setCsvPhase("registering");
  try {
    const result = await importPortfolios(toRegister);
    await qc.invalidateQueries({ queryKey: ["portfolios"] });
    // 기존 parts[] 토스트 메시지 로직 — result 필드로 매핑
    const parts: string[] = [];
    if (result.added.length)               parts.push(`${result.added.length}종목 등록됨`);
    if (result.skipped_duplicate.length)   parts.push(`${result.skipped_duplicate.length} 중복`);
    if (result.skipped_unsupported.length) parts.push(`${result.skipped_unsupported.length} 미지원`);
    if (result.skipped_limit.length)       parts.push(`${result.skipped_limit.length} 한도 초과`);
    result.added.length > 0 ? toast.success(parts.join(" · ")) : toast.info(parts.join(" · ") || "등록된 종목이 없습니다.");
  } catch {
    toast.error("일괄 등록에 실패했습니다. 잠시 후 다시 시도해주세요.");
  }
  resetCsvState();
}, [csvItems, qc, resetCsvState]);
```

## Tech Review (dc-tech-review · 2026-06-25)

### 아키텍처 분해

- **영향 레이어**: backend(`user` 도메인) / frontend(`portfolios/new`)
- **신규**: `BulkImportRequest.java`, `BulkImportResult.java` (record), `PortfolioController.importBulk()`, `PortfolioService.bulkImport()`
- **수정**: `portfolios.ts` (함수+타입 추가), `portfolios/new/page.tsx` (`handleBulkRegister` 교체)
- **DB**: 변경 없음 — 기존 `uq_portfolio_user_stock UNIQUE(user_id, stock_code)` 이중 방어 활용

### 기존 인프라 확인 (구현 시 재사용)

| 재사용 대상 | 위치 | 비고 |
|------------|------|------|
| `StockMasterService.findByStockCodeIn()` | `stocks/services/StockMasterService.java:69` | 이미 존재 — Spec 의사코드의 `findAllByCodes()`는 이 메서드로 교체 |
| `PortfolioRepository.findStockCodesByUserId()` | `PortfolioRepository.java:24` | `List<String>` 반환 → `new HashSet<>(...)` 변환 필요 |
| `SecurityUtils.extractTier(authentication)` | `PortfolioController.java:57` | 동일 패턴으로 `importBulk()`에 적용 |
| `@CacheEvict("portfolioStockCodes", key="#userId")` | `PortfolioService.java:149` | `bulkImport()` 동일 적용 |
| `DB UNIQUE uq_portfolio_user_stock` | `V3__create_portfolios.sql:17` | saveAll() 전 애플리케이션 레벨 중복 분류로 DB 에러 방지 (기존 단건과 동일 패턴) |

### 작업 카드

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `BulkImportRequest` record 추가 (`@NotEmpty @Size(max=50) stock_codes`) | backend/user/dto | 하 | — |
| 2 | `BulkImportResult` record 추가 (`added, skipped_duplicate, skipped_unsupported, skipped_limit` — `List<String>` 4개) | backend/user/dto | 하 | — |
| 3 | `PortfolioService.bulkImport()` 추가 — `findByStockCodeIn` + `findStockCodesByUserId` → 분류 루프 → `saveAll()` + `@CacheEvict` | backend/user/services | 중 | #1 #2 |
| 4 | `PortfolioController.importBulk()` 추가 — `@PostMapping("import")` + `SecurityUtils.extractTier()` | backend/user/controllers | 하 | #3 |
| 5 | `portfolios.ts` — `ImportPortfoliosResult` 인터페이스 + `importPortfolios()` 함수 추가 | frontend/lib/api | 하 | — |
| 6 | `portfolios/new/page.tsx` — `handleBulkRegister` for 루프 제거, `importPortfolios()` 단일 호출+응답 매핑으로 교체 | frontend/portfolios | 중 | #5 |

### 구현 시 주의사항

1. **`findAllByCodes` → `findByStockCodeIn`**: Spec 의사코드 89번째 줄의 `stockMasterService.findAllByCodes()`는 존재하지 않는 메서드. `findByStockCodeIn(Collection<String>)`(캐시 TTL 4h)으로 교체.
2. **`findStockCodesByUserId` 반환 타입**: `List<String>` — `Set<String> existing = new HashSet<>(portfolioRepository.findStockCodesByUserId(userId))`로 사용.
3. **입력 dedup**: FE가 중복 코드를 보내는 경우(`stock_codes: ["005930", "005930"]`) `saveAll()` 시 DB UNIQUE 위반. 분류 루프 진입 전 `stockCodes = new ArrayList<>(new LinkedHashSet<>(stockCodes))`로 입력 dedup 필요.
4. **`@Transactional` + `@CacheEvict` 순서**: `PortfolioService`가 class-level `@Transactional` — `bulkImport()` 메서드에 `@CacheEvict` 추가 시 Spring AOP가 트랜잭션 커밋 후 evict. 정상 동작.
5. **R12(422 방어코드)**: `bulkImport()`는 한도 초과 코드를 `skipped_limit`에 분류하고 절대 422를 반환하지 않음. R12의 422 경쟁 조건은 발생 불가 → FE는 단순히 `catch { toast.error(...) }`로 5xx만 처리.
6. **`@PostMapping("import")` 경로 충돌**: 현재 Controller에 `@PostMapping` (루트) + `@PostMapping("summary")`없음. "import"는 literal이므로 `/{id}` (Long 타입)와 충돌 없음 — 기존 `"summary"` 패턴과 동일.

### DB / 마이그레이션 영향

- **Flyway 마이그레이션 불필요** — DB 스키마 변경 없음.

### 외부 계약 영향

- 변경 없음. DART/KRX/카카오/LLM 비영향.

### 리스크 & 법적 검토

- **금융 PII 보호 (CLAUDE.md §7)**: 벌크 임포트는 `stock_code`만 수신, `avg_buy_price`/`quantity` 미처리 — 법적 요건 충족. `BulkImportRequest`에 이 필드 절대 추가 금지.
- **Free 한도 경쟁 조건**: `bulkImport()` 트랜잭션 내에서 `findStockCodesByUserId()` 후 `saveAll()` 사이에 다른 요청이 삽입될 수 있음. DB UNIQUE 제약이 최종 방어선 — 충돌 시 `DataIntegrityViolationException` → 전체 트랜잭션 롤백. MVP 단계에서 수용 가능.
- **`stocksByCodeIn` 캐시 hit**: `findByStockCodeIn`은 캐시(TTL 4h)를 거침 — 방금 추가된 종목마스터가 캐시 미반영 시 `unsupported`로 오분류 가능. 단, 마스터 데이터는 KRX 배치(월 1회) 기준으로 갱신되므로 실질적 영향 없음.

### 예상 wave 수

- **1 wave** (BE + FE 동시 구현) — 영향 범위 좁고 BE/FE 의존성이 응답 스키마 계약으로만 묶임.
  - `/dc-implement portfolio-csv-upload` 1회로 완료 가능.

---

✅ **기술 검토 완료** — 구현 가능 판단. 다음 단계: `/dc-spec-move portfolio-csv-upload Approved` → `/dc-implement portfolio-csv-upload`
