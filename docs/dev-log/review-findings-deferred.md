---
type: dev-log
status: open
created: 2026-06-01
updated: 2026-06-01
---

# 코드 리뷰 보류 항목 (disclosure-collection-pipeline)

> `/dc-review-code` 리뷰(2026-06-01)에서 발견된 항목 중 현재 세션에서 즉시 수정 불가한 항목.
> 선행 Spec 완료, 의존성 추가, 아키텍처 변경이 필요한 항목들.

---

## [HIGH] N+1 stocks 커버리지 쿼리

**파일**: `DisclosureCollectionService.java` — `isCovered()` 메서드  
**문제**: 공시 항목 1건마다 `SELECT COUNT(*) FROM stocks WHERE stock_code = ?` 쿼리 발생.  
배치 100건 수신 시 최대 100회 DB 왕복.

**해결 방안**:
1. `stocks-master-seed` Spec 완료 후 `Stock` 엔티티 + `StockRepository` 생성
2. `collect()` 진입 시 전체 커버 종목 코드를 Set으로 한 번에 로드:
   ```java
   Set<String> coveredCodes = stockRepository.findAllStockCodes(); // SELECT stock_code FROM stocks
   ```
3. `isCovered(stockCode, coveredCodes)` 로 Set 조회 — DB 쿼리 0회

**선행 조건**: `[[stocks-master-seed]]` Spec Approved + 구현 완료

---

## [HIGH] Thread.sleep 블로킹 재시도 (DartClient)

**파일**: `DartClient.java` — `fetchPageWithRetry()` 메서드  
**문제**: `Thread.sleep(delayMs)`가 스케줄러 스레드를 블로킹. 재시도 최대 3회 × 최대 30초 = 90초 블로킹 가능.  
`fixedDelay` 방식이라 deadlock은 없으나, 스레드 풀이 단일 스레드면 다른 스케줄 작업 지연.

**해결 방안**:
```groovy
// build.gradle에 추가
implementation 'org.springframework.retry:spring-retry'
implementation 'org.springframework:spring-aspects'
```
```java
@Retryable(
    retryFor = RestClientException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 2000, multiplier = 2, maxDelay = 30000)
)
DartListResponse fetchPage(LocalDate bgnDe, LocalDate endDe, int pageNo) { ... }
```
`fetchPageWithRetry()` 메서드 제거 후 `fetchPage()`에 직접 `@Retryable` 적용.

**선행 조건**: `spring-retry` + `spring-aspects` 의존성 추가 결정 (팀 합의 필요)

---

## [HIGH] disclosure 도메인 → infrastructure DTO 직접 의존

**파일**: `DisclosureCollectionService.java`, `DisclosurePollingJob.java` — `DartListResponse.Item` 직접 사용  
**문제**: `disclosure` 도메인이 `infrastructure.dart.dto.DartListResponse.Item`을 직접 임포트.  
도메인 간 직접 의존 금지 규칙(CLAUDE.md §3-2) 위반. DART API 응답 스키마 변경 시 도메인 코드까지 수정 필요.

**해결 방안**:
```java
// shared/event 또는 disclosure/dto에 중간 DTO 생성
public record DisclosureItem(
    String rceptNo, String corpCode, String stockCode,
    String corpName, String reportNm, String rceptDt
) {}
```
- `DartClient.fetchList()` 반환 타입을 `List<DisclosureItem>`으로 변경
- `DisclosureCollectionService`는 `DisclosureItem`만 사용
- `infrastructure` 계층이 변환 책임 보유

**선행 조건**: 아키텍처 리팩토링 Spec 작성 필요 (규모상 별도 작업 카드)

---

## [MEDIUM] lastPolledDate 인메모리 저장 (DisclosurePollingJob)

**파일**: `DisclosurePollingJob.java` — `AtomicReference<LocalDate> lastPolledDate`  
**문제**: 애플리케이션 재시작 시 `lastPolledDate`가 null로 초기화 → 오늘부터 재폴링 시작.  
재시작 직전 수집 안 된 공시(예: 어제 장시간 장애 후 복구 시)가 누락될 수 있음.  
단, `rcept_no` 멱등 처리로 중복 저장은 방지.

**해결 방안 A (DB)**: `system_configs` 테이블에 `last_polled_date` 키 저장 + 기동 시 로드  
**해결 방안 B (Redis)**: `SET dart:lastPolledDate YYYYMMDD`  
MVP 단일 인스턴스에서는 허용 범위 — 분산 배포 전환 시 우선 처리.

**선행 조건**: 운영 환경 배포 아키텍처 결정 (단일 인스턴스 → 멀티 인스턴스 전환 시점)

---

## [MEDIUM] SSRF 취약점 가능성 (DartClient baseUrl)

**파일**: `DartApiProperties.java` — `baseUrl` 필드  
**문제**: `baseUrl`이 환경변수로 주입되므로 외부 공격자가 직접 변경할 수는 없으나,  
설정 오류 또는 공급망 공격으로 내부 서비스 URL이 주입될 경우 SSRF 가능.

**해결 방안**: `DartClient` 생성자에서 URL 화이트리스트 검증 추가:
```java
private static final String ALLOWED_HOST = "opendart.fss.or.kr";

public DartClient(DartApiProperties props) {
    URI uri = URI.create(props.baseUrl());
    if (!ALLOWED_HOST.equals(uri.getHost())) {
        throw new IllegalStateException("Disallowed DART baseUrl host: " + uri.getHost());
    }
    ...
}
```
테스트 환경(`localhost`)도 허용해야 하므로 프로파일 기반 화이트리스트 구성 필요.

**선행 조건**: 테스트 프로파일 분리 전략 결정 (localhost 예외 처리 방식)

---

## [LOW] API 키 에러 로그 노출 위험 (DartClient)

**파일**: `DartClient.java` — `fetchPageWithRetry()` 예외 로그  
**문제**: `e.getMessage()`에 URL 쿼리스트링이 포함될 경우 `crtfc_key=실제키값`이 로그에 출력될 수 있음.  
현재 RestClient가 URI를 로그에 포함시키는지 확인 필요.

**해결 방안**: 예외 메시지에서 API 키 파라미터를 마스킹하는 로그 필터 또는  
`fetchPage()` 내에서 URI를 로그 제외 처리 후 예외 재래핑.

**선행 조건**: 실제 예외 메시지 내용 확인 (운영 환경 1회 검증 필요)
