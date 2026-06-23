---
type: spec
status: Approved
created: 2026-06-21
updated: 2026-06-23
---

# 포트폴리오 리뷰 후속 수정 Spec (portfolio-review-followup)

> 상태: Draft → **Approved** (2026-06-23, dc-tech-review 승인)

## 배경 / 목적

[[portfolio-management-e2e]] 구현 후 `/dc-review-code` 4-에이전트 리뷰에서
H-1(통합 테스트)·M-2(Number 변환)·M-3(스키마 dead code) 3건은 즉시 수정됐다.

본 Spec은 남은 **Medium 3건(M-1·M-4+M-5·M-6) + Low 4건(L-1·L-2·L-3·L-5)**을 다룬다.
Low 2건(L-4 Premium 페이지네이션·L-6 종목 수정)은 별도 Spec으로 분리한다.

- **페르소나**: A(개인 투자자 — 포트폴리오 등록·목록 조회)
- **BM 티어**: Free / Pro / Premium 전 티어 (BE 로직 개선)

---

## 요구사항

### BE — Medium

#### R1 `toResponse(PortfolioEntity)` 단건 오버로드 제거 (M-1)

`PortfolioService.java`의 `private PortfolioResponse toResponse(PortfolioEntity e)` 오버로드 제거.
이 오버로드는 내부에서 `stockRepository.findById()`를 숨겨 호출해 두 가지 문제를 야기한다:

- `getPortfolio()` · `updatePortfolio()` 호출부가 암묵적 DB 쿼리를 발생시켜 토큰 비용 예측이 어려움
- 향후 개발자가 이 경로를 통해 새 변형을 추가할 때 bulk 최적화 우회 패턴이 퍼질 위험

수정 방향:
```java
// 제거 대상
private PortfolioResponse toResponse(PortfolioEntity e) { ... }

// 각 호출부(getPortfolio, updatePortfolio)에서 명시적 조회로 교체
String corpName = stockRepository.findById(e.getStockCode())
        .map(Stock::getCorpName).orElse(null);
return toResponse(e, corpName);
```

> M-5(updatePortfolio/getPortfolio 단건 추가 쿼리)는 R1 수정으로 자동 해결된다.
> R1+R2를 함께 적용하면 Stock 마스터 캐시 히트로 추가 DB 쿼리가 제거된다.

---

#### R2 Stock 마스터 Caffeine 캐시 적용 (M-4)

`StockRepository.findByStockCodeIn()` · `findById()` 호출은 변동성이 극히 낮은
Stock 마스터(분기 1회 배치 갱신)를 매 API 호출마다 DB에서 조회한다.
Caffeine이 이미 프로젝트 스택에 포함되어 있으므로 즉시 적용 가능하다.

수정 방향:
```java
// StockRepository (또는 StockService 위임 레이어) 에 @Cacheable 적용
// 단건 조회
@Cacheable(value = "stockByCode", key = "#stockCode")
Optional<Stock> findById(String stockCode);   // Spring Data JPA PK 조회 재사용

// bulk IN 조회 — 캐시 미히트 stockCode만 DB 조회하는 방식이 이상적이나
// MVP 규모(Free 3·Pro 10·Premium 무제한 포트폴리오)에서는 전체 리스트 캐시로 충분
@Cacheable(value = "stocksByCodeIn", key = "#stockCodes")
List<Stock> findByStockCodeIn(Collection<String> stockCodes);
```

`application.yml` Caffeine 설정 (`stockByCode` TTL 4시간 이상):
```yaml
spring:
  cache:
    caffeine:
      spec: maximumSize=1000,expireAfterWrite=4h
```

`StockMasterSyncJob` 완료 후 `@CacheEvict(value={"stockByCode","stocksByCodeIn"}, allEntries=true)` 추가.

> [[performance-caching-staletime]] R4(application.yml Caffeine 기본 설정)와 병합 적용 가능.
> 해당 Spec이 먼저 머지됐다면 Caffeine 설정 추가 없이 캐시 선언만 추가.

---

### BE — Low

#### R3 BigDecimal 변환 NFE 방어 (L-1)

`PortfolioService.toResponse()` 내 복호화 결과 BigDecimal 변환:
```java
decryptedPrice != null ? new BigDecimal(decryptedPrice) : null
```
`encryptor.decrypt()`가 null이 아닌 손상 문자열을 반환하면 `NumberFormatException` 발생.
500 응답 스택 트레이스에 복호화 시도값이 포함될 수 있어 금융 PII 로그 금지 원칙(CLAUDE.md §7) 위배.

수정 방향:
```java
private static BigDecimal parseSafe(String decrypted) {
    if (decrypted == null) return null;
    try {
        return new BigDecimal(decrypted);
    } catch (NumberFormatException e) {
        // 원문 절대 포함 금지 — 금융 개인정보
        throw new IllegalStateException("암호화 필드 복호화 값 파싱 실패");
    }
}
// toResponse()에서 사용
parseSafe(decryptedPrice), parseSafe(decryptedQty)
```

---

#### R4 `Collectors.toMap()` merge 함수 추가 (L-3)

`listPortfolios()` 내 `corpNameMap` 조립 시 merge 함수 누락:
```java
// 현재
.collect(Collectors.toMap(Stock::getStockCode, Stock::getCorpName));
// → 중복 stockCode(이론상 불가, PK) 시 IllegalStateException

// 수정
.collect(Collectors.toMap(
        Stock::getStockCode,
        Stock::getCorpName,
        (existing, replacement) -> existing  // 중복 방어 — PK 보장이지만 명시적 선언
));
```

---

### FE — Medium

#### R5 `portfolios/new` `avg_buy_price` max 검증 추가 (M-6)

`PortfolioSheet.tsx`는 `max: { value: 999_999_999 }` 검증이 있으나
`portfolios/new/page.tsx`의 `avg_buy_price` register에는 max 검증이 없다.
같은 BE `@DecimalMax(999_999_999)` 제약에 대해 두 진입 경로의 FE 검증이 불일치한다.

수정 방향:
```tsx
// portfolios/new/page.tsx — register("avg_buy_price", {...}) 에 추가
max: { value: 999_999_999, message: "9억 9천만 원을 초과할 수 없습니다" },
```

---

### FE — Low

#### R6 `corp_name` null 시 중립 문자열 처리 (L-2)

`PortfolioListItem.tsx`의 현재 폴백:
```tsx
const displayName = p.corp_name ?? p.stock_code;
```
`corp_name`이 null이면 `stock_code`(공개 정보)가 표시되어 내부 마스터 불완전성이 간접 노출된다.

수정 방향:
```tsx
const displayName = p.corp_name ?? `종목 ${p.stock_code}`;
```
단, `stock_code`를 그대로 표시하는 현재 방식이 투자자에게 더 유용할 수 있다는 의견도 있다.
이 항목은 UI 결정 사항으로 `/dc-review-frontend` 시 최종 확정.

---

#### R7 `PortfolioListItem` Bell 아이콘 처리 (L-5)

R3 옵션 A(per-stock 알림 토글 MVP 제외) 이후 Bell 아이콘이 `aria-hidden` 장식으로 잔존.
알림 설정은 `/notifications/settings`로 일원화됐으므로 아이콘을 링크로 승격하거나 제거한다.

옵션 A (권장): Bell 아이콘을 `/notifications/settings` Link로 교체
```tsx
<Link
  href="/notifications/settings"
  aria-label="알림 설정"
  className={buttonVariants({ variant: "ghost", size: "icon" })}
>
  <Bell className="size-4 text-primary" />
</Link>
```

옵션 B: Bell 아이콘 전체 제거 (UX 단순화)

---

## 영향 범위

- **영향 레이어**: backend(`user/services`) + frontend(`portfolios/new`, `components/domain`)
- **DB 변경**: 없음
- **외부 계약**: 없음 (응답 스키마 변경 없음)

| 파일 | 요구사항 | 변경 내용 |
|------|----------|----------|
| `backend/.../user/services/PortfolioService.java` | R1·R3·R4 | 오버로드 제거, NFE 방어, merge 함수 |
| `backend/.../stocks/repositories/StockRepository.java` | R2 | @Cacheable 선언 |
| `backend/src/main/resources/application.yml` | R2 | stockByCode TTL 설정 |
| `frontend/src/app/(app)/portfolios/new/page.tsx` | R5 | avg_buy_price max 검증 |
| `frontend/src/components/domain/PortfolioListItem.tsx` | R6·R7 | corp_name 폴백 · Bell 링크화 |

---

## 별도 분리 항목

| 항목 | 내용 | 분리 이유 |
|------|------|----------|
| L-4 Premium 페이지네이션 | `listPortfolios()` `Page<>` 전환 + FE cursor | [[performance-caching-staletime]] 또는 `portfolio-pagination` 별도 Spec — MVP 이후 우선순위 |
| L-6 종목 수정(edit=true) | `portfolios/new?edit=` 진입 + `PUT /portfolios/{id}` FE 연결 | `portfolio-edit-mode` 별도 Spec — R4(종목 수정 PATCH)로 분리 명시된 항목 |

---

## 관련 문서

- [[portfolio-management-e2e]] Done — 원 구현 Spec
- [[performance-caching-staletime]] Draft — Caffeine 전역 설정 (R2 병합 대상)
- `docs/dev-log/backend.jsonl` 2026-06-21 — dc-review-code H-1·M-2·M-3 수정 기록

## 리스크 / 법적 검토

- R3(NFE 방어): `parseSafe` 예외 메시지에 `decryptedPrice` 원문 절대 미포함 — 금융 PII(CLAUDE.md §7)
- R2(캐시): `StockMasterSyncJob` evict 누락 시 stale corpName 제공 — 분기 배치 완료 시점 evict 필수

## 권장 구현 순서

- **Wave 1 (BE)**: R1(오버로드 제거) → R2(Caffeine 캐시) → R3(NFE 방어) → R4(merge 함수)
  - R1·R2 의존 순서: R1 먼저 적용하면 R2 캐시 히트 시 getPortfolio/updatePortfolio 추가 쿼리 0건
- **Wave 2 (FE)**: R5(max 검증) → R6(corp_name 폴백 결정) → R7(Bell 링크화)
  - R6는 UI 결정 필요 — `/dc-review-frontend` 시 확정 권장

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-23)

### ⚠️ 전제 재검증 — Spec 작성(2026-06-21) 이후 코드 실측

Spec 작성 후 `performance-caching-staletime`(Done)·`portfolio-management-e2e R3`·포트폴리오 라우트 개편이 머지되어 전제 4건이 어긋났다.

| 항목 | Spec 원전제 | 실측 결과 (2026-06-23) | 판정 |
|------|------------|----------------------|------|
| **R1** toResponse 오버로드 | 단건 오버로드가 `findById` 숨김 | `PortfolioService.java:130` 오버로드 존재, `getPortfolio`(73)·`updatePortfolio`(113)가 호출 | ✅ 유효 |
| **R2** 캐시 적용 방식 | `application.yml`에 `caffeine.spec` 추가 + `@Cacheable findById` | **application.yml은 `CacheConfig.java` 프로그래밍 방식으로 전환됨**(`registerCustomCache`). `findById`는 JpaRepository 기본 메서드 → `@Cacheable` 직접 부착 불가 | 🔧 **구현 방식 전면 정정** |
| **R3** parseSafe NFE 방어 | `new BigDecimal(decrypted)` 무방비 | `PortfolioService.java:144-145` 무방비 확인 | ✅ 유효 |
| **R4** toMap merge 함수 | merge 누락 | `PortfolioService.java:66` merge 함수 없음 확인 | ✅ 유효 |
| **R5** max 검증 대상 파일 | `portfolios/new/page.tsx`의 `avg_buy_price register` | **라우트 개편: `new`=검색 전용, `add`=등록 상세.** `avg_buy_price`는 `portfolios/add/page.tsx:160` `Controller`(rules `min`만, `max` 없음) | 🔧 **대상 `add/page.tsx`로 정정, register→Controller** |
| **R6·R7** PortfolioListItem | corp_name 폴백 · Bell 장식 수정 | **`PortfolioListItem.tsx`는 dead component — 자신 외 import 0건.** `portfolios/page.tsx`가 대시보드로 개편되며 `StatCard` 사용, 이 컴포넌트 미참조 | ⚠️ **dead code — 수정 대신 삭제 권장** |

### 사용자 결정 필요 (1건)

**R6·R7 — PortfolioListItem.tsx 처리 방향**:
- **삭제 (권장)**: dead component(import 0). corp_name 폴백·Bell 링크화는 살아있지 않은 코드를 고치는 것 → 가치 없음. 컴포넌트 + `PortfolioListItem` 관련 dead export 제거가 정합.
- **보존+수정**: 향후 목록 UI 복원 의도가 있다면 R6·R7 원안대로 수정 후 유지. 단 현재 진입점 없음.
- 구현 시점에 `/dc-implement` 또는 사용자 확정으로 결정. 본 검토는 **삭제** 가정으로 카드 구성.

### 아키텍처 분해

- **영향 레이어**: backend(`user/services`, `stocks/repositories`, `stocks`, `shared/config`) + frontend(`portfolios/add`, `components/domain`)
- **신규**: 없음 (CacheConfig에 캐시 등록 추가, StockMasterSyncJob에 evict 추가)
- **수정**: `PortfolioService`(R1·R3·R4), `CacheConfig`(R2 캐시 등록), `StockRepository` 또는 `StockMasterService`(R2 @Cacheable 위임), `StockMasterSyncJob`(R2 evict), `portfolios/add/page.tsx`(R5)
- **삭제**: `PortfolioListItem.tsx`(R6·R7, 삭제안 채택 시)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| **Wave 1 — BE PortfolioService** | | | | | |
| 1 | R1: `toResponse(PortfolioEntity)` 단건 오버로드 제거 → `getPortfolio`/`updatePortfolio` 호출부를 명시적 `findById` corpName 조회로 교체 (M-5 자동 해소) | backend/user | BE | 하 | - |
| 2 | R3: `parseSafe(String)` 헬퍼 추가 — NFE catch 후 원문 미포함 예외. `toResponse(e, corpName)`의 BigDecimal 변환 2곳 교체 | backend/user | BE | 하 | #1 |
| 3 | R4: `listPortfolios()` `Collectors.toMap`에 merge 함수 `(a,b)->a` 추가 | backend/user | BE | 하 | - |
| **Wave 1 — BE Stock 캐시 (R2)** | | | | | |
| 4 | R2: `CacheConfig.java`에 `stockByCode`·`stocksByCodeIn` `registerCustomCache`(TTL 4h+) 추가. `StockMasterService`(또는 명시 메서드)에 `@Cacheable` 위임 — `findById`/`findByStockCodeIn` 경유. `StockMasterSyncJob` 완료 후 `@CacheEvict(allEntries=true)` 추가 | backend/stocks·shared | BE | 중 | - |
| **Wave 2 — FE** | | | | | |
| 5 | R5: `portfolios/add/page.tsx` `avg_buy_price` `Controller` `rules`에 `max: { value: 999_999_999, ... }` 추가 (BE `@DecimalMax` 정합). quantity(233)도 max 누락 시 동반 점검 | frontend/portfolios | FE | 하 | - |
| 6 | R6·R7: `PortfolioListItem.tsx` 삭제(dead component) — 삭제안 확정 시. 보존 시 corp_name 폴백 + Bell 링크화 | frontend/components | FE | 하 | 사용자 결정 |

### DB / 마이그레이션 영향

- **없음.** 컬럼·인덱스·스키마 변경 없음. R2는 애플리케이션 캐시 레이어만 추가.

### 외부 계약 영향

- **없음.** DART/KRX/카카오/LLM 무관. REST 응답 스키마(`PortfolioResponse`) 필드 불변 — R1·R3은 내부 조회 경로만 변경, R4는 동작 동일(방어적).

### 리스크 & 법적 검토

- **금융 PII(중) — R3**: `parseSafe` 예외 메시지·로그에 `decryptedPrice`/`decryptedQty` 원문 절대 미포함(CLAUDE.md §7). 현재 무방비 `new BigDecimal()`은 NFE 스택트레이스에 복호화 시도값 노출 위험 → R3가 이를 차단.
- **stale corpName(하) — R2**: `StockMasterSyncJob`(분기 배치) 완료 후 evict 누락 시 옛 종목명 제공. 카드 #4에 evict 포함 필수. 변동성 극저(분기 1회)라 영향 경미하나 누락 금지.
- **캐시 키 충돌(하) — R2**: `stocksByCodeIn` 키가 `Collection` 순서/타입에 민감. `Set` 전달 시 동등성 보장 확인 — MVP 규모(Free 3·Pro 10)에서는 단건 `stockByCode` 캐시만으로도 충분, bulk 캐시는 선택.
- **dead code 회귀(하) — R6·R7**: 삭제 시 `PortfolioListItem` import 잔존 여부 빌드 확인(현재 0건이므로 안전).

### 예상 wave 수

- **2 wave**:
  - **Wave 1**(BE): 카드 #1~#4. R1→R3 의존(같은 메서드), R4·R2 독립. 1 PR.
  - **Wave 2**(FE): 카드 #5·#6. R5 즉시, R6·R7은 삭제/보존 결정 후. 1 PR.

### 확인 필요 (구현 시점)

1. **카드 #4 (R2)** — `@Cacheable`을 `StockRepository`(JpaRepository) 기본 `findById`에 직접 부착 불가. `StockMasterService`에 위임 메서드를 두거나, Repository에 `findByStockCode`(명시 쿼리 메서드)를 추가해 부착. `performance-caching-staletime`의 `AnalysisResultCacheService` 분리 패턴(Optional 언랩 SpEL 이슈 회피) 참고 권장.
2. **카드 #6 (R6·R7)** — `PortfolioListItem.tsx` 삭제 vs 보존 사용자 확정. 삭제가 기본 권장(dead code).
3. **카드 #5 (R5)** — `add/page.tsx` quantity register(233줄)도 max 누락 여부 확인해 동반 수정 검토.
