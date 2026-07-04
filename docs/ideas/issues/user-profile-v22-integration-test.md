---
type: issue
status: Closed
created: 2026-06-23
updated: 2026-06-25
resolved: 2026-06-25
source: dc-review-code (user-profile-investment-experience Wave 1+2)
priority: P2
---

# PATCH /users/me V22 시나리오 Testcontainers 통합 테스트 미비

> **상태**: Closed — 2026-06-25 해결.
> `AuthIntegrationTest.java`에 5케이스 추가 — profileOnly/mixed/invalidExperience/emptyNickname/newUserNullFields.
> 기존 `AuthIntegrationTest`는 nickname 단일 필드만 커버.

## 배경

V22 마이그레이션으로 `PATCH /users/me`에 다음 변경이 생겼다:

1. `nickname` → nullable (`@Size(min=1)` 유지, `@NotBlank` 제거)
2. `investmentExperience` / `preferredTime` 신규 선택 필드 (`@Pattern` 검증)
3. `GET /users/me` 응답에 두 필드 추가

이 변경에 대한 Testcontainers 통합 테스트가 없다.
Mock DB 사용 금지 원칙(CLAUDE.md §6-6) → 반드시 Testcontainers PostgreSQL 사용.

## 미검증 시나리오 (5건)

| # | 시나리오 | 기대 결과 |
|---|---------|-----------|
| 1 | `{ investment_experience: "BEGINNER", preferred_time: "REALTIME" }` (nickname 없음) | 200, DB 저장 확인 |
| 2 | `{ nickname: "newName", investment_experience: "ADVANCED" }` (mixed) | 200, nickname+experience만 갱신 |
| 3 | `{ investment_experience: "INVALID_VALUE" }` | 400 ConstraintViolation |
| 4 | `{ nickname: "" }` (빈 문자열) | 400 @Size(min=1) 위반 |
| 5 | `GET /users/me` 응답 → `investment_experience`/`preferred_time` 필드 포함 | null or 저장값 반환 |

## 왜 지금 못 하나

- 현재 세션은 구현+리뷰 완료 직후 → 별도 테스트 스펙 Wave로 분리 예정
- `AuthIntegrationTest.java` 확장 필요 (약 50~80줄)
- V22 마이그레이션이 Testcontainers PostgreSQL에 자동 적용되는지 Flyway 설정 확인 필요

## 수정 방향

`backend/src/test/java/com/dartcommons/user/AuthIntegrationTest.java`에 내부 클래스 또는 메서드로 추가:

```java
@Test
void updateMe_profileOnly_noNickname_returns200() {
    // signup + login → access token 취득
    // PATCH /users/me { investment_experience: "BEGINNER", preferred_time: "REALTIME" }
    // 200 OK + GET /users/me 재조회 → investment_experience = "BEGINNER"
}

@Test
void updateMe_invalidExperience_returns400() {
    // PATCH /users/me { investment_experience: "INVALID" }
    // 400 Bad Request + 필드 에러 메시지 확인
}

@Test
void updateMe_emptyNickname_returns400() {
    // PATCH /users/me { nickname: "" }
    // 400 Bad Request (@Size min=1 위반)
}

@Test
void getMe_includesProfileFields() {
    // 신규 가입 후 GET /users/me
    // investment_experience = null, preferred_time = null 확인
}
```

## 다음 단계

- [ ] `AuthIntegrationTest.java` 위 4+1건 추가
- [ ] V22 마이그레이션이 테스트 Testcontainers에서 자동 적용되는지 확인
  (`spring.flyway.locations=classpath:db/migration` 설정 상속 여부)
- [ ] 우선순위 P2 — 다음 BE 변경 Wave 또는 별도 test-verify 단계에서 처리
