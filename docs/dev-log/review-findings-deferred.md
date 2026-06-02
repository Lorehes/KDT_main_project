---
type: dev-log
status: resolved
created: 2026-06-01
updated: 2026-06-02
---

# 코드 리뷰 보류 항목 (disclosure-collection-pipeline) — 전부 해결 ✅

> `/dc-review-code`(2026-06-01) 후속으로 발견된 deferred 6건 + 보안 게이트 1건.
> 2026-06-02 단일 세션에서 전부 해결. 원본 항목 본문은 git history(commit `197c885`) 참조.

## 해결 이력

| 우선순위 | 항목 | 해결 |
|---|---|---|
| HIGH | N+1 stocks 커버리지 쿼리 | `Stock` 엔티티 + `StockRepository.findAllStockCodes()` — 배치당 1회 Set 로드 |
| HIGH | `Thread.sleep` 블로킹 재시도 | `spring-retry` 도입, `DartPageFetcher.fetchPage` / `KrxClient.fetchAllBasicInfo`에 `@Retryable`, `SchedulingConfig`에 `@EnableRetry` |
| HIGH | `disclosure` → `infrastructure` DTO 직접 의존 | `disclosure/dto/RawDisclosureItem` 도메인 DTO 도입, `DartClient.fetchList()` 반환 타입 변경, 변환 책임 infrastructure로 이전 |
| MEDIUM | `lastPolledDate` 인메모리 | V11 `system_configs` + `SystemConfig` 엔티티/리포지토리, `DisclosurePollingJob` 재기동 복원 |
| MEDIUM | SSRF 화이트리스트 부재 | `HostWhitelist` 유틸 + 모든 외부 클라이언트 생성자 `baseUrl` 호스트 검증 |
| LOW | API 키 에러 로그 노출 | `SecretMasker` 유틸 + 외부 클라이언트 예외 메시지 마스킹 |
| 보안 게이트 | BackfillController 인증 | Spring Security 도입, `SecurityConfig` HTTP Basic `/admin/**` 강제, `AdminAuthIntegrationTest` 3건 통과 |

## 통합 검증

- 모든 변경 후 통합 테스트 **14/14 통과** (Application 1 + Disclosure 6 + Backfill 4 + AdminAuth 3).
- 의존성 추가: `spring-boot-starter-security`, `spring-security-test`, `spring-retry`, `spring-aspects`.
- 신규 마이그레이션: `V11__create_system_configs.sql`.
