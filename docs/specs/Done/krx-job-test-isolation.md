---
type: spec
status: Approved
created: 2026-06-24
updated: 2026-06-24
---

# KRX 배치 잡 테스트 환경 격리 Spec

> 상태: **Approved** (2026-06-24, dc-tech-review 승인 — 카드 3 전역 disable 권장안 채택)

## 배경 / 목적

`dashboard-eval-pnl` 구현 코드리뷰(`/dc-review-code`) 결과, `KrxPriceSyncJob`에 테스트 환경 격리 장치가 없어 `@SpringBootTest` 기반 통합 테스트 실행 시 실제 KRX API 호출이 발생할 수 있음이 LOW 이슈로 확인됨.

현재 문제:
1. **스케줄 잡 자동 실행**: `@Scheduled(cron = "0 0 18 * * MON-FRI")` 어노테이션이 `@SpringBootTest` 컨텍스트에서도 활성화됨. 테스트 실행 중 18:00 KST에 실제 KRX API를 호출할 가능성이 있음.
2. **@ConditionalOnProperty 미설정**: `DisclosurePollingJob`에는 `@MockitoBean`으로 억제하는 패턴이 이미 존재하나, `KrxPriceSyncJob`은 명시적 비활성화 수단이 없음.
3. **B128 HTTP 명시화**: `B128_URL`이 `http://`(평문)로 하드코딩되어 있음. KRX가 HTTPS를 지원하는지 확인되지 않아 LOW 리스크로 열려 있음.

## 요구사항

### R1 — KrxPriceSyncJob @ConditionalOnProperty (우선순위: Low)
- [x] `application.yml`에 `dartcommons.krx.price-sync.enabled` 프로퍼티 추가 (기본값: `true`)
- [x] `KrxPriceSyncJob`에 `@ConditionalOnProperty(name = "dartcommons.krx.price-sync.enabled", havingValue = "true", matchIfMissing = true)` 추가
- [x] 통합 테스트 `@TestPropertySource`에 `dartcommons.krx.price-sync.enabled=false` 추가 → 잡 Bean 자체가 컨텍스트에서 제외됨 (Mock 불필요)

### R2 — B128 HTTP → HTTPS 조사 (우선순위: Low)
- [x] `http://data.krx.co.kr/comm/bldAttendant/executeForResourceBundle.cmd` → `https://` 전환 시 응답 정상 여부 확인
  - curl HTTP 200 확인 (2026-06-24) → `B128_URL` 상수를 `https://`로 교체 완료

### R3 — @TestPropertySource 표준 세트 갱신 (우선순위: Low)
- [x] `src/test/resources/application.yml`에 `dartcommons.krx.price-sync.enabled=false` 전역 추가 — 20개 `@SpringBootTest` 컨텍스트 일괄 비활성화 (Spec 원안 단건 갱신 대신 전역 권장안 채택)
- [x] `KrxPriceSyncJobIntegrationTest.java` `@TestPropertySource`에 `enabled=true` + `@MockitoBean KrxClient` 패턴 유지

## 영향 범위 (조사 결과)

- 영향 레이어: backend(stocks, infrastructure/krx)
- 영향 파일:
  - `backend/src/main/java/com/dartcommons/stocks/KrxPriceSyncJob.java` — @ConditionalOnProperty 추가
  - `backend/src/main/resources/application.yml` — 프로퍼티 추가
  - `backend/src/main/java/com/dartcommons/infrastructure/krx/KrxClient.java` — B128_URL `http→https` (R2 결과에 따라)
  - `backend/src/test/java/com/dartcommons/user/PortfolioIntegrationTest.java` — @TestPropertySource 갱신
- DB 변경: 없음
- 외부 계약: 없음

## 관련 패턴 / 과거 사례

- `DisclosurePollingJob` 억제: 기존 통합 테스트에서 `@MockitoBean DisclosurePollingJob pollingJob`으로 스케줄 잡 억제. @ConditionalOnProperty 방식은 Mock 없이 Bean 자체 제거로 더 깔끔.
- `@TestPropertySource` 표준 세트: `eval-pnl-integration-tests.md` Spec 참조. `dartcommons.krx.base-url=http://localhost`를 이미 포함.

## 리스크 / 법적 검토

- `@ConditionalOnProperty`는 Bean 자체를 컨텍스트에서 제외하므로, `KrxPriceSyncJobIntegrationTest`에서 `@Autowired KrxPriceSyncJob`을 사용하려면 해당 테스트 클래스에서는 `enabled=true`로 유지해야 함 (단, `@MockitoBean KrxClient`로 실제 API 차단).
- B128 HTTP: 최근 거래일 메타데이터(날짜 문자열)만 전송 — 개인정보·금융 데이터 없음. MITM 실질 피해 낮으나 HTTPS가 가능하면 전환 권장.

## 권장 구현 방향

### R1 구현 예시

`KrxPriceSyncJob.java`:
```java
@ConditionalOnProperty(name = "dartcommons.krx.price-sync.enabled", havingValue = "true", matchIfMissing = true)
@Component
@RequiredArgsConstructor
public class KrxPriceSyncJob {
    // 기존 코드 그대로 — 어노테이션만 추가
}
```

`application.yml`:
```yaml
dartcommons:
  krx:
    price-sync:
      enabled: true   # false로 설정 시 KrxPriceSyncJob Bean 비활성화 (테스트·로컬 개발용)
```

`PortfolioIntegrationTest.java` `@TestPropertySource` 추가:
```java
@TestPropertySource(properties = {
    // 기존 프로퍼티들 ...
    "dartcommons.krx.price-sync.enabled=false"   // KrxPriceSyncJob Bean 비활성화
})
```

### 구현 우선순위

R1(@ConditionalOnProperty) → R2(B128 HTTP 조사) → R3(표준 세트 갱신) 순으로 진행.
R2는 단순 curl 확인이므로 R1 구현 시 함께 처리 가능.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-24)

### 코드 대조로 확인된 전제 차이 (구현 전 반영 필요)

Spec 작성 시점 가정과 실제 레포 상태가 3곳 어긋남 — 작업 카드는 **실제 상태 기준**으로 재구성:

1. **R3 "신규 `KrxPriceSyncJobIntegrationTest.java`"는 이미 존재함.**
   `src/test/java/com/dartcommons/stocks/KrxPriceSyncJobIntegrationTest.java`가 이미 `@MockitoBean KrxClient` + `@Autowired KrxPriceSyncJob` 패턴으로 작성되어 있음(eval-pnl-integration-tests Spec 산출물). → "신규 작성"이 아니라 "@ConditionalOnProperty 도입 후 `enabled=true` override 추가"만 필요.

2. **R2 B128 HTTP 조사는 사실상 완료 상태.**
   `KrxClient.java:55` 주석에 이미 `"HTTP(not HTTPS) 응답 정상 확인"`이 기록됨. R2는 신규 조사가 아니라 **재확인 + 날짜 스탬프 보강**(Spec R2가 요구한 `"KRX HTTP-only 확인됨 (YYYY-MM-DD)"` 포맷) 수준. 독립 카드로 분리.

3. **R3 비활성화 범위가 과소.** Spec은 `PortfolioIntegrationTest` 한 곳만 갱신하자고 함. 그러나 `@SpringBootTest` 컨텍스트는 약 20개 존재하고(`AuthIntegrationTest`, `PricingIntegrationTest`, `ConsentIntegrationTest`, `Notification*`, `Stage2AnalyzerIntegrationTest` 등), 이들 대부분이 `KrxPriceSyncJob`을 Mock하지 않아 실 Bean+`@Scheduled`가 살아있음. `@ConditionalOnProperty(matchIfMissing=true)`만 추가하고 한 테스트만 끄면 **나머지 ~19개 컨텍스트는 여전히 노출**.
   → **권장: 공유 `src/test/resources/application.yml`에 `dartcommons.krx.price-sync.enabled: false` 한 줄 추가.** 모든 `@SpringBootTest`가 이 파일을 로드하므로 전역 1회로 비활성화. `KrxPriceSyncJobIntegrationTest`만 자체 `@TestPropertySource`로 `enabled=true` override(이미 `@MockitoBean KrxClient`로 실 API 차단). 개별 테스트 N곳을 만지는 것보다 정합·유지보수 우수.

### 아키텍처 분해
- 영향 레이어: backend(stocks, infrastructure/krx, shared/config 무관), test(공유 리소스 + stocks)
- 신규: 없음(클래스/컴포넌트 신규 0). 수정: 어노테이션 1 + 프로퍼티 3 + 주석 1
- Stage 1~5 파이프라인 영향: 없음. `@EnableScheduling`(SchedulingConfig)은 변경 없음 — Bean 자체를 조건부 제외.

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `KrxPriceSyncJob`에 `@ConditionalOnProperty(name="dartcommons.krx.price-sync.enabled", havingValue="true", matchIfMissing=true)` 추가 | backend/stocks | BE | 하 | - |
| 2 | `src/main/resources/application.yml`에 `dartcommons.krx.price-sync.enabled: true` 추가(운영 기본 ON) | backend/resources | BE | 하 | #1 |
| 3 | **(권장)** `src/test/resources/application.yml`에 `dartcommons.krx.price-sync.enabled: false` 추가 — 전역 비활성화(20개 `@SpringBootTest` 일괄) | backend/test-resources | BE | 하 | #1,#2 |
| 4 | `KrxPriceSyncJobIntegrationTest` `@TestPropertySource`에 `dartcommons.krx.price-sync.enabled=true` override 추가(실 Bean 필요, `@MockitoBean KrxClient` 유지) | backend/test/stocks | BE | 하 | #3 |
| 5 | (R2) `curl -I https://data.krx.co.kr/...B128.bld` 재확인 → HTTPS 정상이면 `B128_URL` 교체, 아니면 `KrxClient.java:55` 주석에 `HTTP-only 확인됨 (2026-06-24)` 날짜 스탬프 | backend/infrastructure/krx | BE | 하 | - (독립) |

> 카드 3은 Spec R3의 "PortfolioIntegrationTest 단건 갱신"을 대체하는 권장안. Spec 원안(개별 테스트 갱신)을 고수할 경우 ~20개 파일을 모두 만져야 하므로 비권장. 사용자 승인 시 카드 3 채택.

### DB / 마이그레이션 영향
- **없음.** DDL/컬럼/인덱스 변경 0건. Flyway 신규 파일 불필요.

### 외부 계약 영향
- **없음.** DART/카카오/LLM 무관. KRX는 B128 URL 스킴(http→https) 검토뿐 — 응답 스키마·파싱 로직(`max_work_dt`) 불변. 카드 5에서 HTTPS 전환 시에도 엔드포인트·필드 동일.

### 리스크 & 법적 검토
- **자본시장법·금융 개인정보·LLM 환각: 해당 없음** (테스트 인프라 + 스케줄 격리 작업, 분석/표현/사용자 데이터 무관).
- **실 API 노출 위험(원 LOW 이슈)**: `@Scheduled(cron="0 0 18 * * MON-FRI")`는 18:00 KST 평일에만 발화 — 시간창이 좁아 실제 발생 확률 낮음. 단 CI가 그 시각에 돌면 실 KRX 호출 가능. 카드 3(전역 disable)으로 모든 컨텍스트 봉쇄, 유일 예외인 카드 4 테스트는 `@MockitoBean KrxClient`로 실 호출 원천 차단 → 잔여 위험 0.
- **B128 HTTP MITM**: 전송 데이터는 최근 거래일 날짜 문자열뿐(개인정보·금융데이터 없음). 실질 피해 낮음. HTTPS 가능 시 전환 권장이나 불가 시 현상 유지 + 주석 명시로 종결(카드 5).
- **`matchIfMissing=true` 의도**: 운영/로컬에서 프로퍼티 누락 시 잡이 ON으로 동작(안전 기본값). 비활성화는 명시적 `false`로만 — 운영 사고 방지.

### 예상 wave 수
- **1 wave.** 5개 카드 모두 난이도 하·기계적이며 DB/외부계약 변경 없음. 카드 1~4(격리)는 한 PR로 묶고, 카드 5(R2 HTTPS 재확인)는 동일 PR에 포함 가능(curl 1회). 분리 불필요.
