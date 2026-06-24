---
type: issue
status: open
created: 2026-06-24
updated: 2026-06-24
---

# [이슈] KRX 이상치 필터 테스트 커버리지 없음

> 상태: **Open** — dc-review-code Low 미결. [[eval-pnl-integration-tests]] Spec R2-추가·R3에 추적 중.

## 현상

`krx-price-source-resilience` Wave 1 구현(`/dc-review-code`) 후 Low 이슈로 확인:

| 대상 | 현황 |
|------|------|
| `KrxClient.isValidPrice()` 경계값 단위 테스트 | ❌ 없음 |
| `KrxPriceSyncJob` ±50% 이상치 스킵 통합 테스트 | ❌ 없음 |
| `KrxPriceSyncJob` 최초 적재(null prevPrice) 허용 검증 | ❌ 없음 |

## 발견 경위

`krx-price-source-resilience` 코드리뷰 Low — 이상치 필터(`isValidPrice`, `ANOMALY_THRESHOLD ±50%`)에 대한 경계값 및 경로 테스트가 없어, 임계값 조정 시 회귀를 자동으로 감지할 수 없음.

## 영향 범위

- `backend/src/main/java/com/dartcommons/infrastructure/krx/KrxClient.java` — `isValidPrice()` private 메서드
- `backend/src/main/java/com/dartcommons/stocks/KrxPriceSyncJob.java` — `syncPrices()` 내 상대 이상치 분기

## 누락 테스트 케이스

### 단위 테스트 — `KrxClientTest.java` (신규, Testcontainers 불필요)

| 케이스 | 입력 | 기대 결과 |
|--------|------|-----------|
| `isValidPrice_zeroPrice_skipped` | CSV `"005930,0"` | 반환 Map에 `005930` 없음 |
| `isValidPrice_negativePrice_skipped` | CSV `"005930,-100"` | Map에 키 없음 |
| `isValidPrice_belowOne_skipped` | CSV `"005930,0.99"` | Map에 키 없음 |
| `isValidPrice_exactlyOne_included` | CSV `"005930,1"` | Map에 포함, 가격=1 |
| `isValidPrice_normalPrice_included` | CSV `"005930,60000"` | Map에 포함 |
| `isValidPrice_malformedPrice_skipped` | CSV `"005930,--"` | NFE 스킵, Map에 없음 |

### 통합 테스트 추가 — `KrxPriceSyncJobIntegrationTest.java` (R2-추가)

| 케이스 | 시나리오 | 기대 결과 |
|--------|----------|-----------|
| `syncPrices_anomalyPrice_skipsUpdate` | 전일 10,000원 → 4,999원 신규(±50% 초과) | DB close_price 미갱신 |
| `syncPrices_withinThreshold_updates` | 전일 10,000원 → 6,000원 신규(±40%) | DB close_price = 6,000 |
| `syncPrices_nullPrevPrice_alwaysAllowed` | close_price NULL(최초 적재) | 어떤 가격이든 UPDATE |

## 리스크

- `isValidPrice()`는 `private` 메서드 — 퍼블릭 API(`fetchClosePricesFromKrx` / `fetchClosePricesFromGithubCache`)를 통한 간접 검증 필요
- `KrxClient` 생성자가 `HostWhitelist.verify()` 호출 → MockRestServiceServer 또는 `@SpringBootTest + @MockitoBean` 환경 필요
- `ANOMALY_THRESHOLD = 0.5` 상수 조정 시 `syncPrices_anomalyPrice_skipsUpdate` 케이스 수정 필요 — 상수와 케이스를 함께 관리할 것

## 다음 단계

1. `eval-pnl-integration-tests` Spec이 Approved 승격될 때 R2-추가·R3 함께 구현
2. `/dc-tech-review eval-pnl-integration-tests` 시 작업 카드에 포함

## 관련

- [[krx-price-source-resilience]] — 이슈 발견 계기 (2026-06-24 코드리뷰 Low)
- [[eval-pnl-integration-tests]] — 테스트 케이스 추적 Spec (R2-추가·R3 보강됨)
- CLAUDE.md §6-6 — "BE 통합 테스트는 Mock DB 금지 → Testcontainers PostgreSQL 사용"
