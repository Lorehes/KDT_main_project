---
type: spec
status: Draft
created: 2026-06-24
updated: 2026-06-24
---

# eval-pnl 통합 테스트 보강 Spec

> 상태: **Draft** (dc-review-code HIGH 이슈 → dc-plan 기록)

## 배경 / 목적

`dashboard-eval-pnl` 구현 코드리뷰(`/dc-review-code`) 결과, 신규 엔드포인트와 배치 잡에 대한 Testcontainers 통합 테스트가 없음이 HIGH 이슈로 확인됨.  
CLAUDE.md §6-6: "BE 통합 테스트는 Mock DB 금지 → Testcontainers PostgreSQL 사용".

커버해야 할 두 컴포넌트:
1. `GET /api/v1/portfolios/summary` — AES 복호화 + 종가 조회 + 집계 + 0나눗셈 방어
2. `KrxPriceSyncJob.syncPrices()` — KrxClient 폴백 + stocks.close_price 갱신 + 캐시 evict

## 요구사항

### R1 — PortfolioIntegrationTest.java 확장 (summary 엔드포인트)
- [ ] `summary_noPortfolios_returns200WithZeros`: 포트폴리오 없음 → `priced_count=0`, `total_cost_basis=0`, `total_pnl=0`, `as_of=null`
- [ ] `summary_snakeCaseFieldNames_verified`: 응답 JSON 필드가 snake_case임을 명시 검증 (`total_cost_basis` 키 존재 / `totalCostBasis` 키 부재) — @JsonProperty 회귀 방지
- [ ] `summary_withClosePriceViaDb_aggregatesCorrectly`: JdbcTemplate로 `stocks.close_price` 직접 삽입 → 종가 있는 종목 1개 → `total_cost_basis`, `total_eval_amount`, `total_pnl`, `pnl_rate`, `priced_count=1` 수학적 정확성 검증
- [ ] `summary_nullClosePrice_countsAsUnpriced`: 종가 NULL 종목 → `unpriced_count=1`, `priced_count=0`
- [ ] `summary_nullAvgBuyPrice_countsAsUnpriced`: avgBuyPrice/quantity 미입력(null) 포트폴리오 → `unpriced_count=1`
- [ ] `summary_mixedPortfolios_splitCounts`: 종가 있는 1개 + 종가 없는 1개 혼재 → `priced_count=1`, `unpriced_count=1`

### R2 — KrxPriceSyncJobIntegrationTest.java 신규 (배치 잡)
- [ ] `syncPrices_updatesClosePrice_inDb`: KrxClient MockitoBean → `fetchAllClosePrices()` stub → `syncPrices()` 직접 호출 → `stocks.close_price` / `price_asof` DB 갱신 확인 (JdbcTemplate)
- [ ] `syncPrices_emptyPriceMap_doesNotOverwrite`: 빈 Map stub → syncPrices() → 기존 close_price 유지
- [ ] `syncPrices_evictsCache_freshPriceServedNextQuery`: syncPrices() 후 summary API 재호출 → 최신 종가 반영 확인 (캐시 evict 간접 검증)

#### R2-추가: 이상치 필터 통합 케이스 (krx-price-source-resilience 코드리뷰 Low)
- [ ] `syncPrices_anomalyPrice_skipsUpdate`: 전일 종가 10,000원인 종목에 6,000원(±40% → 허용) vs 4,999원(±50% 초과 → 스킵) 각각 stub → DB 반영 여부 검증
- [ ] `syncPrices_nullPrevPrice_alwaysAllowed`: `close_price` NULL인 종목(최초 적재) → 어떤 가격이든 UPDATE 허용 확인

### R3 — KrxClientTest.java (isValidPrice 단위 테스트 — Testcontainers 불필요)
> 참고: `KrxClient.isValidPrice()`는 `private` — 패키지-프라이빗 또는 reflection 없이 퍼블릭 API(`fetchClosePricesFromKrx()` 반환 Map)를 통해 간접 검증.  
> KrxClient 생성자가 HostWhitelist 검증(`HostWhitelist.verify()`)을 호출하므로 MockRestServiceServer 또는 `@SpringBootTest`(`@MockitoBean`) 환경 필요.

- [ ] `isValidPrice_zeroPrice_skipped`: stub CSV `"005930,0\n"` → 반환 Map에 `005930` 키 없음 확인
- [ ] `isValidPrice_negativePrice_skipped`: stub CSV `"005930,-100\n"` → 반환 Map에 키 없음
- [ ] `isValidPrice_belowOne_skipped`: stub CSV `"005930,0.99\n"` → 반환 Map에 키 없음
- [ ] `isValidPrice_exactlyOne_included`: stub CSV `"005930,1\n"` → Map에 `005930` 포함, 가격 = 1
- [ ] `isValidPrice_normalPrice_included`: stub CSV `"005930,60000\n"` → Map에 포함
- [ ] `isValidPrice_malformedPrice_skipped`: stub CSV `"005930,--\n"` → NFE로 스킵, Map에 키 없음 (WARN 로그 확인)

## 영향 범위 (조사 결과)

- 영향 레이어: backend(user, stocks, infrastructure/krx)
- 추가 테스트 파일:
  - `backend/src/test/java/com/dartcommons/user/PortfolioIntegrationTest.java` — 기존 클래스에 케이스 추가
  - `backend/src/test/java/com/dartcommons/stocks/KrxPriceSyncJobIntegrationTest.java` — 신규 클래스
  - `backend/src/test/java/com/dartcommons/infrastructure/krx/KrxClientTest.java` — 신규 단위 테스트 (R3, Testcontainers 불필요)
- DB 변경: 없음
- 외부 계약: 없음 (KrxClient는 @MockitoBean으로 대체, R3는 MockRestServiceServer)

## 관련 패턴 / 과거 사례

- 기존 패턴: `PortfolioIntegrationTest.java` — `@SpringBootTest + @AutoConfigureMockMvc + @Import(TestcontainersConfiguration.class)`
- JdbcTemplate 직접 DB 조작: `createPortfolio_sensitiveFieldsEncryptedInDb()` 에서 `avg_buy_price_enc` BYTEA 조회
- MockitoBean 패턴: `@MockitoBean DisclosurePollingJob pollingJob` — 스케줄 잡 억제
- V10 시드 데이터 종목코드 사용 가능: `005930`(삼성전자), `000660`(SK하이닉스), `402340`(SK스퀘어)
- `signupAndGetToken()` / `createPortfolio()` 헬퍼 재사용

## 리스크 / 법적 검토

- 복호화 값(avgBuyPrice·quantity)은 테스트 코드에서도 로그 출력 금지 (CLAUDE.md §7) — `assertThat`으로 수치 비교만
- `pnl_rate` 단정 수치 비교 시 `isEqualByComparingTo()`(BigDecimal 정밀도 안전) 사용

## 권장 구현 방향

### PortfolioIntegrationTest 확장 방법
JdbcTemplate로 `stocks.close_price` 주입:
```java
// close_price 직접 주입 헬퍼 — KrxPriceSyncJob 불필요
jdbcTemplate.update(
    "UPDATE stocks SET close_price = ?, price_asof = CURRENT_DATE WHERE stock_code = ?",
    new BigDecimal("60000"), "005930");
```

### KrxPriceSyncJobIntegrationTest 구조
```java
@MockitoBean KrxClient          krxClient;
@MockitoBean DisclosurePollingJob pollingJob;  // 배경 잡 억제
@Autowired   KrxPriceSyncJob    krxPriceSyncJob;
@Autowired   JdbcTemplate       jdbcTemplate;

// stub
given(krxClient.fetchAllClosePrices()).willReturn(Map.of(
    "005930", new StockCloseInfo(new BigDecimal("60000"), LocalDate.now())
));

krxPriceSyncJob.syncPrices();

BigDecimal saved = jdbcTemplate.queryForObject(
    "SELECT close_price FROM stocks WHERE stock_code = '005930'", BigDecimal.class);
assertThat(saved).isEqualByComparingTo(new BigDecimal("60000"));
```

### snake_case 회귀 테스트 (필수)
```java
// JsonNode로 key 존재 여부 확인 — @JsonProperty 회귀 방지
assertThat(json.has("total_cost_basis")).isTrue();
assertThat(json.has("totalCostBasis")).isFalse();   // camelCase 절대 없어야 함
```

## 구현 시 참고 `@TestPropertySource` 표준 세트

```java
@TestPropertySource(properties = {
    "dartcommons.dart.api-key=test-key",
    "dartcommons.dart.base-url=http://localhost",
    "dartcommons.krx.api-key=test-key",
    "dartcommons.krx.base-url=http://localhost",
    "dartcommons.admin.username=admin",
    "dartcommons.admin.password=test-admin-password",
    "dartcommons.llm.provider=mock"
})
```

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
