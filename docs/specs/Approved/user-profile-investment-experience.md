---
type: spec
status: Approved
created: 2026-06-16
updated: 2026-06-23
---

# 투자 경험 · 주 사용 시점 DB 저장 Spec

> 상태: Draft → **Approved** (2026-06-23, dc-tech-review 승인)

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

## Tech Review (dc-tech-review · 2026-06-23)

### ⚠️ 전제 재검증 — Spec 작성(2026-06-16) 이후 코드 실측

| 항목 | Spec 원전제 | 실측 결과 | 판정 |
|------|------------|-----------|------|
| **마이그레이션 버전** | "V9가 최신 → V10" | 실제 최신은 **V21**(`V21__nullable_agreed_at.sql`). V10은 과거 사용됨 | ❌ **V22로 정정** |
| **profile nickname 조달** | "user!.nickname — 이미 fetchMe() 호출됨" | `terms/page.tsx:44` `useSignup`이 fetchMe 호출 → 정상 경로 profile 진입 시 user 존재 ✅. 단 **profile 직접 진입/새로고침 시 Zustand 초기화로 user=null** | ⚠️ **방어 필요 → nickname 선택화** |
| **FE 훅** | "apiClient 직접 호출" | `useUpdateMe()` 훅 이미 존재(`auth.ts:108`) — apiClient 직접 호출 대신 훅 사용 | 🔧 **훅 사용으로 변경** |
| **UpdateMeRequest** | nickname만 존재 | `UpdateMeRequest.java:13` nickname `@NotBlank`만 — 확장 필요 확인 | ✅ 유효 |
| **UserMeResponse** | 필드 추가 필요 | `UserMeResponse.java` 두 필드 없음 확인 | ✅ 유효 |
| **updateProfile()** | UserEntity에 추가 | `UserEntity.java` `updateNickname`/`updateNotifySettings` 패턴 존재, `updateProfile` 없음 | ✅ 유효 |

### 사용자 결정 (2026-06-23)

**nickname 선택화 채택** — `UpdateMeRequest.nickname`을 `@NotBlank` → **nullable**로 변경:

- `nickname != null`이면 갱신, `null`이면 스킵(`UserService.updateMe()` null-safe 분기).
- profile 단계는 nickname 없이 `{ investment_experience, preferred_time }`만 전송 → **새로고침으로 user=null이어도 안전**.
- 기존 마이페이지 닉네임 변경은 nickname 포함 전송 → 동일 동작(호환).
- **반려**: Spec 원안(nickname 동봉) — profile 새로고침 시 user=null이면 저장 실패. 별도 엔드포인트 — DTO/Controller/Service 증가로 과설계.
- **주의**: `@NotBlank` 제거 시 빈 문자열(`""`) 닉네임 허용 위험 → null 허용하되 `@Size(min=1, max=50)` 유지하고 서비스에서 `nickname != null` 체크(빈 문자열은 size 위반으로 400).

### 아키텍처 분해

- **영향 레이어**: backend(user) + frontend(signup/profile, lib)
- **신규**: `V22__add_profile_fields_to_users.sql`, `UserEntity.InvestmentExperience`/`PreferredTime` enum
- **수정**: `UserEntity`(필드+updateProfile), `UpdateMeRequest`(nickname nullable + 2필드), `UserMeResponse`(2필드), `UserService.updateMe()`(null-safe), `authStore.AuthUser`, `auth.ts UpdateMeBody`, `signup/profile/page.tsx`(useUpdateMe 호출 복원)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| **Wave 1 — BE 스키마 + 도메인** | | | | | |
| 1 | `V22__add_profile_fields_to_users.sql` — `investment_experience`/`preferred_time` nullable + CHECK 제약 (V7 패턴, NOT NULL DEFAULT 없음) | backend/user | BE | 하 | - |
| 2 | `UserEntity` — `InvestmentExperience`/`PreferredTime` enum + 필드 2개(`@Enumerated(STRING)`) + `updateProfile()` null-safe 메서드 | backend/user | BE | 하 | #1 |
| **Wave 1 — BE DTO + 서비스** | | | | | |
| 3 | `UpdateMeRequest` — nickname `@NotBlank`→nullable(`@Size` 유지), `investmentExperience`/`preferredTime` String 필드 추가(`@Pattern` enum 검증). `UserMeResponse` — `investment_experience`/`preferred_time` 추가. `UserService.updateMe()` — nickname null-safe + `valueOf()` 파싱 후 `updateProfile()` 호출 | backend/user | BE | 중 | #2 |
| **Wave 2 — FE 타입 + 호출 복원** | | | | | |
| 4 | `authStore.AuthUser` + `auth.ts UpdateMeBody` — `investment_experience`/`preferred_time` optional 필드 추가 | frontend/lib | FE | 하 | #3 |
| 5 | `signup/profile/page.tsx` — `useUpdateMe()` 호출 복원(nickname 미전송, 두 필드만), 머리 주석 정정("BE 미지원" 제거), 미선택 시 빈 문자열→undefined 변환 | frontend/signup | FE | 하 | #4 |

### DB / 마이그레이션 영향

- **필요** — `backend/src/main/resources/db/migration/V22__add_profile_fields_to_users.sql` (신규)
- 두 컬럼 모두 **nullable**(기존 가입자 null 유지) + CHECK 제약. V7 패턴 참고하되 `NOT NULL DEFAULT` 없음.
- `ddl-auto: validate` 유지 — V22 미적용 상태로 엔티티 필드 추가 시 기동 실패. 마이그레이션 선적용 필수.
- **Flyway 불변**: V22 적용 후 수정 금지, 롤백은 V23 `DROP COLUMN`.

### 외부 계약 영향

- **없음.** DART/KRX/카카오/LLM 무관. 자체 REST `PATCH /users/me` 요청 스키마에 optional 필드 2개 추가(하위 호환 — 기존 nickname-only 요청 정상 동작) + `GET /users/me` 응답 필드 2개 추가.

### 리스크 & 법적 검토

- **자본시장법 §11.1(중)**: `investment_experience`는 **해석 복잡도 조정** 목적으로만 사용. "상급자이니 매수하세요" 등 경험 기반 투자 권유 표현 절대 금지(CLAUDE.md §7).
- **개인정보(하)**: 투자 경험·시점은 단순 UX 설정값 — 민감정보 미해당, 암호화 불필요(통합기획서 §11.1 금융 개인정보와 구분).
- **nickname nullable 회귀(중)**: `@NotBlank` 제거가 빈 문자열 닉네임을 허용하지 않도록 `@Size(min=1)` 유지 + 서비스 null 체크 필수. 기존 마이페이지 닉네임 변경 동작 회귀 여부를 통합 테스트로 확인.
- **enum 파싱 400(하)**: 잘못된 값(`valueOf` 실패)은 `IllegalArgumentException` → `GlobalExceptionHandler`에서 400 매핑되는지 확인 필요(기존 `ConstraintViolationException` 핸들러와 별개 — `@Pattern` 우선 검증이면 무관).

### 예상 wave 수

- **2 wave**:
  - **Wave 1**(BE): 카드 #1·#2·#3. 마이그레이션→엔티티→DTO/서비스 순서. 1 PR.
  - **Wave 2**(FE): 카드 #4·#5. 타입 확장 후 호출 복원. 1 PR. Wave 1 배포 후 진행(BE 필드 선존재 필요).

### 확인 필요 (구현 시점)

1. **카드 #3** — `UserService.updateMe()`의 enum `valueOf()` 실패 시 400 응답 경로. `@Pattern`으로 컨트롤러 단계 차단이 더 깔끔(서비스 도달 전 400).
2. **카드 #5** — profile 단계에서 인증 토큰 보유 확인. `terms`에서 `useSignup`이 `storeTokenCookies` 수행하므로 정상 경로 토큰 존재(apiClient 인증 헤더 주입 가능).
