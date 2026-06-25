---
type: issue
status: Closed
created: 2026-06-23
updated: 2026-06-25
resolved: 2026-06-25
source: dc-review-code (portfolio-review-followup Wave 1+2)
priority: P2
---

# portfolio-review-followup 캐시 hit/miss + parseSafe 테스트 공백

> **상태**: Closed — 2026-06-25 해결.
> `PortfolioIntegrationTest.listPortfolios_secondCall_hitsCacheNotDb` (Caffeine estimatedSize 검증) + `PortfolioServiceParseSafeTest` (parseSafe null·NFE·정상 3케이스).

## 배경

`portfolio-review-followup` Wave 1에서 다음 변경이 이루어졌다.

1. **R2**: `StockMasterService.findByStockCode/@findByStockCodeIn` — `@Cacheable` SpEL 키 적용. `CacheConfig.stockByCode`(TTL 4h) / `stocksByCodeIn`(TTL 4h) 등록.
2. **R3**: `PortfolioService.parseSafe()` — 복호화 결과 BigDecimal 변환 시 NFE를 catch하고 PII 미포함 예외로 재발생.

이 두 경로가 통합 또는 단위 테스트로 검증되지 않은 채 머지됨.

---

## 미검증 시나리오 (2그룹 4건)

### 그룹 A: 캐시 hit/miss 검증

기존 `PortfolioIntegrationTest.listPortfolios_success_returns200`은 응답 본문(corp_name 값)을 검증하지만,
`findByStockCodeIn`이 실제로 캐시를 히트하는지 여부는 검증하지 않는다.
SpEL `T(java.util.TreeSet).new(#stockCodes).toString()` 키가 미묘하게 비결정적이면
캐시 히트율이 0%이어도 기능 테스트는 통과한다 — 성능 저하가 프로덕션에서만 표면화됨.

| # | 시나리오 | 기대 결과 |
|---|---------|-----------|
| A-1 | `GET /portfolios` 2회 연속 호출 → `StockMasterService.findByStockCodeIn` 1회만 DB 도달 | `@Spy` 또는 Mockito verify로 2번째 호출이 캐시 히트임을 확인 |
| A-2 | `StockMasterSyncJob`(또는 `stockMasterService.sync()` 직접 호출) 후 캐시 무효화 → 3번째 `GET /portfolios` 다시 DB 조달 | evict 후 캐시 미스 확인 |

### 그룹 B: parseSafe() NFE 경로

`parseSafe`는 복호화 성공 but non-numeric 문자열 반환 시 경로. `encryptor.decrypt()` 자체는 성공하지만
반환값이 손상된 경우(암호화 키 부분 교체, 레거시 인코딩 등). 현재 100% 미테스트.

| # | 시나리오 | 기대 결과 |
|---|---------|-----------|
| B-1 | `parseSafe(null)` → `null` 반환 | ✅ |
| B-2 | `parseSafe("NOT_A_NUMBER")` → `IllegalStateException` 발생, 메시지에 원문 미포함 | 예외 메시지에 `"NOT_A_NUMBER"` 포함 금지 (금융 PII, CLAUDE.md §7) |

---

## 왜 지금 못 하나

- `parseSafe`가 `private static` → `PortfolioService` 전체 컨텍스트 없이 단위 테스트 불가. Testcontainers 컨텍스트 또는 `parseSafe` package-private 추출 중 선택 필요.
- 그룹 A의 spy 기반 캐시 검증은 `@SpringBootTest` 환경에서 `@SpyBean StockMasterService` + 캐시 수동 초기화(`CacheManager.getCache(...).clear()`) 조합이 필요 — 기존 테스트 설정 이해 후 추가해야 함.
- 현재 세션은 Wave 1+2 구현 완료 직후 → 별도 test-verify wave로 분리.

---

## 수정 방향

### 그룹 A — `PortfolioIntegrationTest.java` 확장

```java
@SpyBean StockMasterService stockMasterService;  // 클래스 상단에 추가

@Test
@DisplayName("listPortfolios 2회 호출 — 2번째는 캐시 히트(DB 미도달)")
void listPortfolios_secondCall_hitsCacheNotDb() throws Exception {
    // 캐시 초기화(테스트 격리)
    cacheManager.getCache("stocksByCodeIn").clear();

    String token = signupAndGetToken(uniqueEmail());
    createPortfolio(token, "005930");
    createPortfolio(token, "000660");

    // 1번째 호출 — 캐시 미스 → DB
    mockMvc.perform(get("/api/v1/portfolios").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk());

    // 2번째 호출 — 캐시 히트 → DB 미도달
    mockMvc.perform(get("/api/v1/portfolios").header("Authorization", "Bearer " + token))
           .andExpect(status().isOk());

    // findByStockCodeIn은 1번만 실제 호출 (2번째는 캐시 반환)
    verify(stockMasterService, times(1)).findByStockCodeIn(anyCollection());
}
```

> `@Autowired CacheManager cacheManager` 추가 필요.
> `@SpyBean`과 `@Cacheable` 프록시의 상호작용 주의 — SpyBean이 캐시 프록시 바깥에 있으면 verify가 캐시 히트도 카운트할 수 있음. 실측 후 조정.

### 그룹 B — `parseSafe` 테스트 방법 선택지

**옵션 1** (권장): `parseSafe`를 `PortfolioService` 내 `package-private static`으로 변경 → 동일 패키지 단위 테스트

```java
// PortfolioService.java
static BigDecimal parseSafe(String decrypted) { ... }   // private → package-private
```

```java
// PortfolioServiceParseSafeTest.java (단위 테스트, Testcontainers 불필요)
@Test void parseSafe_null_returnsNull() {
    assertThat(PortfolioService.parseSafe(null)).isNull();
}
@Test void parseSafe_invalidString_throwsIllegalState_withoutPii() {
    assertThatThrownBy(() -> PortfolioService.parseSafe("NOT_A_NUMBER"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("NOT_A_NUMBER");  // PII 미포함 검증
}
```

**옵션 2**: `PortfolioIntegrationTest`에 `AesGcmEncryptor` 조작으로 손상 데이터 삽입 → Testcontainers 환경에서 통합 테스트. 설정 복잡도 高.

---

## 관련 파일

- `backend/src/test/java/com/dartcommons/user/PortfolioIntegrationTest.java` — 그룹 A 추가 대상
- `backend/src/main/java/com/dartcommons/user/services/PortfolioService.java:149` — `parseSafe()` 위치
- `backend/src/main/java/com/dartcommons/stocks/services/StockMasterService.java:64` — `findByStockCodeIn` @Cacheable
- `backend/src/main/java/com/dartcommons/shared/config/CacheConfig.java` — `stockByCode`/`stocksByCodeIn` 등록

## 다음 단계

- [ ] `PortfolioIntegrationTest`에 A-1·A-2 시나리오 추가 (`@SpyBean` + `CacheManager.clear()`)
- [ ] `parseSafe` 접근 제어자를 package-private으로 완화 후 B-1·B-2 단위 테스트 추가
- [ ] 우선순위 P2 — 다음 BE 변경 Wave 또는 `/dc-test-verify` 단계에서 처리
