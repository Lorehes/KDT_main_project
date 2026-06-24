---
type: spec
status: Draft
created: 2026-06-24
updated: 2026-06-24
---

# KRX 배치 잡 테스트 환경 격리 Spec

> 상태: **Draft** (dc-plan 생성 — dc-review-code LOW 이슈 기록)

## 배경 / 목적

`dashboard-eval-pnl` 구현 코드리뷰(`/dc-review-code`) 결과, `KrxPriceSyncJob`에 테스트 환경 격리 장치가 없어 `@SpringBootTest` 기반 통합 테스트 실행 시 실제 KRX API 호출이 발생할 수 있음이 LOW 이슈로 확인됨.

현재 문제:
1. **스케줄 잡 자동 실행**: `@Scheduled(cron = "0 0 18 * * MON-FRI")` 어노테이션이 `@SpringBootTest` 컨텍스트에서도 활성화됨. 테스트 실행 중 18:00 KST에 실제 KRX API를 호출할 가능성이 있음.
2. **@ConditionalOnProperty 미설정**: `DisclosurePollingJob`에는 `@MockitoBean`으로 억제하는 패턴이 이미 존재하나, `KrxPriceSyncJob`은 명시적 비활성화 수단이 없음.
3. **B128 HTTP 명시화**: `B128_URL`이 `http://`(평문)로 하드코딩되어 있음. KRX가 HTTPS를 지원하는지 확인되지 않아 LOW 리스크로 열려 있음.

## 요구사항

### R1 — KrxPriceSyncJob @ConditionalOnProperty (우선순위: Low)
- [ ] `application.yml`에 `dartcommons.krx.price-sync.enabled` 프로퍼티 추가 (기본값: `true`)
- [ ] `KrxPriceSyncJob`에 `@ConditionalOnProperty(name = "dartcommons.krx.price-sync.enabled", havingValue = "true", matchIfMissing = true)` 추가
- [ ] 통합 테스트 `@TestPropertySource`에 `dartcommons.krx.price-sync.enabled=false` 추가 → 잡 Bean 자체가 컨텍스트에서 제외됨 (Mock 불필요)

### R2 — B128 HTTP → HTTPS 조사 (우선순위: Low)
- [ ] `http://data.krx.co.kr/comm/bldAttendant/executeForResourceBundle.cmd` → `https://` 전환 시 응답 정상 여부 확인
  - 확인 방법: `curl -I https://data.krx.co.kr/comm/bldAttendant/executeForResourceBundle.cmd?baseName=krx.mdc.i18n.component&key=B128.bld`
  - 응답이 정상이면 `B128_URL` 상수를 `https://`로 교체
  - 응답이 301/302 리다이렉트면 따라가도 정상인지 확인
  - HTTPS가 작동 안 하면 현 상태 유지 + 주석에 "KRX HTTP-only 확인됨 (YYYY-MM-DD)"으로 명시

### R3 — @TestPropertySource 표준 세트 갱신 (우선순위: Low)
- [ ] 기존 `PortfolioIntegrationTest.java` `@TestPropertySource`에 `dartcommons.krx.price-sync.enabled=false` 추가
- [ ] 신규 `KrxPriceSyncJobIntegrationTest.java`에는 `enabled=true` + `@MockitoBean KrxClient` 패턴 사용 (실제 API 대신 stub)

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
