---
type: spec
status: Approved
created: 2026-06-24
updated: 2026-06-24
---

# eval-pnl 통합 테스트 보강 Spec

> 상태: Draft → **Approved** (2026-06-24, dc-tech-review 승인)

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

## Tech Review (dc-tech-review · 2026-06-24)

### 아키텍처 분해

- **영향 레이어**: backend(user — PortfolioIntegrationTest 확장) + backend(stocks — 신규 통합) + backend(infrastructure/krx — 신규 단위)
- **신규 vs 수정**:
  - 수정: `PortfolioIntegrationTest.java` — R1 6케이스 추가 (기존 8케이스 패턴 재사용: `signupAndGetToken`/`createPortfolio`/JdbcTemplate)
  - 신규: `KrxPriceSyncJobIntegrationTest.java` — R2 3케이스 + R2-추가 2케이스
  - 신규: `KrxClientTest.java` — R3 6케이스 (isValidPrice 경계값)
- **테스트 전용 변경** — 프로덕션 코드 무수정. DB·외부 계약 영향 없음.

### 검증된 정합성 (실제 코드 대조 완료)

- `PortfolioSummaryResponse` 8필드 모두 Spec 케이스 참조와 일치 — `total_cost_basis`·`total_eval_amount`·`total_pnl`·`pnl_rate`·`priced_count`·`unpriced_count`·`as_of` (`@JsonProperty` snake_case 적용 확인)
- 엔드포인트: `GET /api/v1/portfolios/summary` (literal mapping, `/{id}` 충돌 없음 — 컨트롤러 주석 확인)
- 시드 종목 V10: `005930`(삼성전자)·`000660`(SK하이닉스)·`402340`(SK스퀘어) — close_price NULL 상태로 적재됨 (R1 unpriced 케이스 활용 가능)
- 기존 패턴: `createPortfolio_sensitiveFieldsEncryptedInDb()`가 JdbcTemplate BYTEA 조회 — R1 close_price 주입도 동일 방식

### 핵심 판정 — R3 (KrxClientTest) 난이도 상향: 하 → 중상

`KrxClient` 생성자는 `KrxApiProperties`만 받아 **내부에서 RestClient를 빌드**(`RestClient.builder()...build()`)한다. 따라서 `MockRestServiceServer`를 외부에서 바인딩할 seam이 없다. 또한 생성자가 `HostWhitelist.verify()`를 호출하므로 순수 단위 생성도 제약.

→ **R3 구현 옵션 (택1, dc-implement 시 결정)**:
- **Option A (권장 — 최소 변경)**: `@SpringBootTest` + `@MockitoBean`으로 `restClient`/`externalRestClient`를 직접 stub. `fetchClosePricesFromKrx`/`fetchClosePricesFromGithubCache`는 private이므로 퍼블릭 `fetchAllClosePrices()` 경유로 간접 검증. 단점: Stage 게이팅 로직(직접→폴백) 때문에 정밀 케이스 분리 어려움.
- **Option B (테스트 친화 리팩터)**: `KrxClient` 생성자에 `RestClient` 2개를 주입받도록 변경(빌드 책임을 `@Configuration`으로 이동). 그러면 `MockRestServiceServer.bindTo()` 가능 → CSV stub 정밀 주입. **프로덕션 코드 변경 수반** — 별도 카드로 분리.
- **Option C (범위 축소)**: R3를 통합(KrxPriceSyncJobIntegrationTest의 KrxClient @MockitoBean stub)으로 흡수하고, isValidPrice 경계값은 별도 순수 단위로 분리하지 않음. isValidPrice를 package-private으로 완화하면 직접 호출 단위 테스트 가능(최소 변경).

→ **권장**: **Option C** — isValidPrice를 `private` → package-private으로 완화하고 `KrxClientTest`에서 직접 호출. 가장 적은 비용으로 경계값(null·0·0.99·1·음수) 6케이스 정밀 검증. 리팩터(Option B)는 과함.

> **확정 (2026-06-24, 사용자 승인)**: **Option C 채택**. 카드 #4로 구현, 카드 #5(Option B 리팩터)는 **미채택 대안**으로 보존(향후 KrxClient 테스트 정밀도 추가 요구 시 재검토).

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `PortfolioIntegrationTest`에 R1 6케이스 추가 (summary: zeros·snake_case·정상집계·nullClosePrice·nullAvgBuyPrice·mixed) | backend/user(test) | BE | 중 | - |
| 2 | `KrxPriceSyncJobIntegrationTest` 신규 — R2 3케이스(updates·emptyMap·evictCache) | backend/stocks(test) | BE | 중 | - |
| 3 | #2에 R2-추가 2케이스(anomaly ±50% skip·nullPrev 허용) | backend/stocks(test) | BE | 중 | #2 |
| 4 | `KrxClient.isValidPrice` package-private 완화(Option C) + `KrxClientTest` R3 6경계값 | backend/infra(test+소폭 가시성) | BE | 중 | - |
| 5 | (대안) Option B 채택 시 — `KrxClient` 생성자 RestClient 주입 리팩터 + `KrxClientConfig` | backend/infra | BE | 상 | - (배타적: #4와 택1) |

### DB / 마이그레이션 영향

- **마이그레이션 없음** — 테스트 전용. R1은 JdbcTemplate `UPDATE stocks SET close_price` 런타임 주입(스키마 불변).

### 외부 계약 영향

- **DART/KRX/카카오/LLM 계약 변경 없음**. `KrxClient`는 `@MockitoBean`(통합) 또는 직접 호출(단위)로 대체 — 실제 KRX 호출 없음.

### 리스크 & 법적 검토

- **복호화 값 로그 금지**(CLAUDE.md §7): R1 케이스에서 `avgBuyPrice`·`quantity` 복호화 값은 `assertThat` 수치 비교만, 로그 출력 금지. `pnl_rate`는 `isEqualByComparingTo()`로 BigDecimal 정밀도 안전 비교.
- **@Scheduled 빈 등록**: `KrxPriceSyncJob`은 현재 `@ConditionalOnProperty` 없이 `@Component`+`@Scheduled` — 테스트 컨텍스트에 스케줄러 등록됨(cron 18:00이라 자동실행은 없으나). `KrxClient` `@MockitoBean`으로 실 호출 차단. **근본 격리는 [[krx-job-test-isolation]] Spec 대상** — 본 Spec과 의존(먼저/병행 처리 권장).
- **테스트 격리**: 각 케이스는 UUID 이메일 유저 독립 생성(기존 패턴). 단 R2/R3는 `stocks.close_price`를 변경하므로 케이스 간 순서 의존 주의 — 각 케이스 시작 시 대상 종목 close_price를 명시적 세팅(`UPDATE ... SET close_price = NULL/값`)할 것.

### 예상 wave 수

- **1 wave** — 카드 #1~#4(테스트 추가 + isValidPrice 가시성 소폭 완화)는 단일 PR. 프로덕션 로직 변경 없음(가시성만).
- 카드 #5(Option B 리팩터)는 **채택 시 별도 wave** — #4와 배타적이므로 dc-implement 진입 시 Option C(권장) vs B 확정 후 하나만 구현.
- [[krx-job-test-isolation]]은 본 Spec의 @Scheduled 격리 리스크와 겹침 — **먼저 또는 병행** 처리 시 카드 #2/#3 안정성↑.
