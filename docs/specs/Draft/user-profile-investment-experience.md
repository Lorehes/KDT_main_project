---
type: spec
status: Draft
created: 2026-06-16
updated: 2026-06-16
---

# 투자 경험 · 주 사용 시점 DB 저장 Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

회원가입 STEP 4/4 프로필 화면(`/signup/profile`)에서 **투자 경험**(`investment_experience`)과
**주 사용 시점**(`preferred_time`)을 UI로 수집하고 있으나, `handleSubmit`의 `apiClient` 호출이
주석 처리되어 있어 선택값이 DB에 저장되지 않는 상태.

- 페르소나 C(입문 투자자) — "공시 용어부터 쉽게 풀어드려요" 개인화 필요
- 페르소나 A(개인 투자자) — 해석 복잡도 조정으로 핵심 요약 vs 상세 데이터 분기
- BM: Free · Pro · Premium 공통 (개인화 가입 흐름)

## 요구사항

- [ ] `users` 테이블에 `investment_experience` · `preferred_time` 컬럼 추가 (Flyway V10)
- [ ] `UserEntity`에 `InvestmentExperience` · `PreferredTime` enum + 필드 + `updateProfile()` 추가
- [ ] `UpdateMeRequest`에 두 필드 추가 (nullable · 선택)
- [ ] `UserMeResponse`에 두 필드 포함 (GET /users/me 응답에 노출)
- [ ] `UserService.updateMe()`에서 null-safe 필드 업데이트 처리
- [ ] FE `AuthUser` 타입에 두 필드 추가
- [ ] FE `UpdateMeBody` 타입에 두 필드 추가 (optional)
- [ ] `/signup/profile` `handleSubmit`의 주석 처리된 API 호출 복원 (nickname은 authStore에서 조달)

## 영향 범위

- 영향 레이어: `backend(user)` / `frontend(signup/profile, lib)`
- DB 변경: **필요** — Flyway V10 마이그레이션 (V9가 최신)

| 파일 | 변경 유형 |
|---|---|
| `backend/src/main/resources/db/migration/V10__add_profile_fields_to_users.sql` | 신규 |
| `backend/.../user/entities/UserEntity.java` | 수정 — enum 2개 + 필드 2개 + `updateProfile()` |
| `backend/.../user/dto/UpdateMeRequest.java` | 수정 — nullable 필드 2개 추가 |
| `backend/.../user/dto/UserMeResponse.java` | 수정 — 응답 필드 2개 추가 |
| `backend/.../user/services/UserService.java` | 수정 — `updateMe()` null-safe 분기 |
| `frontend/src/lib/stores/authStore.ts` | 수정 — `AuthUser` 타입 확장 |
| `frontend/src/lib/api/auth.ts` | 수정 — `UpdateMeBody` 타입 확장 |
| `frontend/src/app/(auth)/signup/profile/page.tsx` | 수정 — API 호출 복원 |

- 외부 계약: 없음 (내부 DB + REST API만)

## 관련 패턴 / 과거 사례

- V7 마이그레이션 패턴 참고: `V7__add_notification_settings_to_users.sql`
  — `ALTER TABLE users ADD COLUMN ... VARCHAR(N) NOT NULL DEFAULT '...'` + CHECK constraint + enum 동기화
- `UserEntity.updateNotifySettings()` — null-safe 다중 필드 업데이트 패턴 참고 (`UserEntity.java:135`)
- `UpdateMeRequest` 확장 주석: `UserController.java:22` "PATCH에 nickname 외 필드 추가 시 UpdateMeRequest 확장"
- 과거 solutions 없음 (docs/solutions 미생성)

## 리스크 / 법적 검토

- **투자 권유 표현 금지**: `investment_experience` 값은 해석 복잡도 조정 목적 — "이 티어에서 매수하세요" 등
  투자 판단을 유도하는 표현으로 활용 금지 (통합기획서 §11.1, CLAUDE.md §7)
- **개인정보**: 투자 경험·시점 정보는 민감정보 미해당(단순 UX 설정값). 별도 암호화 불필요.
- **Flyway 불변 원칙**: V10 적용 후 파일 수정 금지. 롤백 필요 시 V11로 `DROP COLUMN`.
- **ddl-auto**: `validate` 유지 — V10 마이그레이션 없이 서버 기동 시 스키마 불일치 오류 발생
  (컬럼 추가 후 앱 재기동 전 마이그레이션 먼저 실행 필요).

## 권장 구현 방향

### DB 스키마 (V10)

```sql
-- V10__add_profile_fields_to_users.sql
ALTER TABLE users
    ADD COLUMN investment_experience VARCHAR(15)
        CHECK (investment_experience IN ('BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    ADD COLUMN preferred_time VARCHAR(10)
        CHECK (preferred_time IN ('REALTIME', 'LUNCH', 'EVENING'));
-- 기존 사용자 null 허용 — 미설정 시 FE 기본값(INTERMEDIATE·REALTIME) 표시
```

> V7 패턴과 달리 `NOT NULL DEFAULT` 없음. 두 필드 모두 **선택사항(nullable)** — 기존 가입자는 null 유지.

### BE 구현 요점

**UserEntity enum 추가**:
```java
public enum InvestmentExperience { BEGINNER, INTERMEDIATE, ADVANCED }
public enum PreferredTime        { REALTIME, LUNCH, EVENING }
```

**updateProfile() 추가** (기존 `updateNickname()` · `updateNotifySettings()` 패턴 유지):
```java
public void updateProfile(InvestmentExperience experience, PreferredTime preferredTime) {
    if (experience   != null) this.investmentExperience = experience;
    if (preferredTime != null) this.preferredTime       = preferredTime;
}
```

**UpdateMeRequest 확장** — `nickname`은 `@NotBlank` 유지, 신규 필드는 nullable:
```java
public record UpdateMeRequest(
    @NotBlank @Size(min = 1, max = 50) String nickname,
    String investmentExperience,   // null이면 스킵
    String preferredTime           // null이면 스킵
) {}
```
> `investmentExperience` / `preferredTime` 값 검증: `@Pattern(regexp="BEGINNER|INTERMEDIATE|ADVANCED")` 등 추가 가능.
> MVP에서는 서비스 레이어에서 `InvestmentExperience.valueOf()` 시 `IllegalArgumentException` → 400으로 충분.

**UserService.updateMe() 변경점**:
- nickname은 기존과 동일하게 항상 업데이트
- 신규 필드는 null 체크 후 `updateProfile()` 호출

**UserMeResponse 필드 추가**:
```java
@JsonProperty("investment_experience") String investmentExperience,
@JsonProperty("preferred_time")        String preferredTime,
```

### FE 구현 요점

**profile/page.tsx `handleSubmit` 복원**:
```typescript
const { user } = useAuthStore();   // 이미 가입 완료 후 fetchMe() 호출됨

const handleSubmit = async () => {
  await updateMe.mutateAsync({
    nickname: user!.nickname,         // @NotBlank 유지 → 현재 닉네임 그대로 전달
    investment_experience: experience,
    preferred_time: time,
  });
  router.push("/signup/complete");
};
```

> `useUpdateMe()` 훅(`auth.ts`) 사용. 성공 시 `fetchMe()` 자동 호출로 authStore 갱신.

**AuthUser 타입 추가**:
```typescript
investment_experience?: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
preferred_time?:        "REALTIME" | "LUNCH" | "EVENING";
```

**UpdateMeBody 타입 추가** (optional):
```typescript
export interface UpdateMeBody {
  nickname: string;
  investment_experience?: "BEGINNER" | "INTERMEDIATE" | "ADVANCED";
  preferred_time?:        "REALTIME" | "LUNCH" | "EVENING";
}
```

### 트레이드오프: 별도 엔드포인트 vs 기존 PATCH 확장

| | 기존 PATCH /users/me 확장 (권장) | 새 PATCH /users/me/profile |
|---|---|---|
| 파일 수 | 적음 (+1 migration) | 많음 (+DTO +Controller +Service 메서드) |
| 기존 FE 호환 | nickname 필수 유지 → 호환 | 엔드포인트 분리 → 명확 |
| 복잡도 | 낮음 | 높음 |

**결론**: 기존 `PATCH /users/me` 확장이 변경 최소화·일관성 면에서 우선.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
