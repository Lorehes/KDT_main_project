---
type: issue
status: Open
created: 2026-06-23
updated: 2026-06-23
source: dc-review-code (dashboard-real-data Wave 2)
priority: P2
---

# DisclosureQueryService Free 티어 강제 로직 Testcontainers 통합 테스트 누락

> **상태**: Open — 기능은 동작하나 회귀 방지 테스트 없음. P2 테스트 부채.

## 현상

`DisclosureQueryService.list()`에 `tier == FREE` 분기가 추가됐으나 (dashboard-real-data R3),
대응하는 Testcontainers 통합 테스트가 없다.

- 구현 파일: `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureQueryService.java:69-79`
- 관련 컨트롤러: `backend/.../disclosure/controllers/DisclosureController.java`

## 영향 시나리오 (미검증)

| 시나리오 | 기대값 | 현재 검증 |
|----------|--------|-----------|
| `tier=FREE, scope=portfolio` 요청 | `fromDate/toDate=오늘(Seoul)`, `page=0`, `size≤5` 강제 | ❌ |
| `tier=FREE, scope=all` 요청 | 403 FORBIDDEN | ❌ |
| `tier=PRO, scope=portfolio` 요청 | FE 전달 파라미터 그대로 | ❌ |
| `tier=FREE` 요청 시 `total_elements` | 오늘 전체 건수 반환 (size 클램핑 전) | ❌ |
| FE `total_elements > 5` 배너 트리거 | 6건 이상 오늘 공시 시 배너 표시 | ❌ (E2E 없음) |

## 수정 방향

### 기존 통합 테스트 패턴 참조

```
backend/src/test/java/com/dartcommons/user/AuthIntegrationTest.java
backend/src/test/java/com/dartcommons/user/PortfolioIntegrationTest.java
```

Testcontainers PostgreSQL 사용 (`@SpringBootTest` + `@Testcontainers`).
Mock DB 금지 (CLAUDE.md §6-6).

### 필요 테스트 케이스 (최소)

```java
// DisclosureQueryServiceIntegrationTest.java (신규)
// 1. FREE 티어: fromDate/toDate → 오늘(Seoul) 강제, size ≤ 5
// 2. FREE 티어: scope=all → 403
// 3. PRO 티어: 파라미터 그대로 전달
// 4. FREE 티어: total_elements는 오늘 전체 건수 반환 (size=5 클램핑 관계없이)
```

## 다음 단계

- [ ] `DisclosureQueryServiceIntegrationTest` 작성 (Testcontainers PostgreSQL)
- [ ] CI에서 회귀 방지 확인
- [ ] FE `isFreeLimited` 배너 조건 E2E (Playwright, tier=FREE + 6건 시드)
