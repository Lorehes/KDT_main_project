---
type: spec
status: Done
created: 2026-06-16
updated: 2026-06-23
---

# OAuth 동의 데이터 정합성 개선 Spec

> 상태: Draft → Approved (2026-06-23) → **Done** (2026-06-23, dc-push Wave 1+3 완료)

## 배경 / 목적

코드 리뷰(2026-06-16) 결과 식별된 이슈 3건 해소.

- **E3 (High-Correctness)**: `autoSignup()` 시 `termsAgreedAt=now()` / `privacyAgreedAt=now()`를 계정 생성 시점(동의 전)으로 설정. 실제 동의는 나중에 `consent_logs`에 기록되어 두 소스 간 시각 불일치. `users.terms_agreed_at NOT NULL` 제약으로 단순 제거 불가 → Flyway V19 마이그레이션 필요.
- **M-P2 (Medium-Performance)**: `oauthCallback()`의 기존 사용자 경로에서 매 로그인마다 `hasRequiredConsents()` DB 조회 발생. 동의 완료 사용자도 매번 `consent_logs` SELECT → Caffeine 캐시 적용으로 DB 부하 절감.
- **M-M2 (Medium-Maintainability)**: `autoSignup()` 이탈 시 `consent_logs` 없는 좀비 계정 누적. 정보통신망법 상 미사용 개인정보 보유 위험. `AuthService.java` 주석에 배치 언급만 있고 미구현.

전체 사용자 영향. Free 티어 포함.

## 요구사항

### Wave 1 — UserEntity agreed_at 불일치 해소 (E3)
- [ ] **R1** — Flyway V19: `users.terms_agreed_at`, `users.privacy_agreed_at` 컬럼을 `NULL` 허용으로 변경
- [ ] **R2** — `UserEntity`: `termsAgreedAt`, `privacyAgreedAt` 필드 `nullable = true`로 변경 (JPA validate 호환)
- [ ] **R3** — `AuthService.autoSignup()`: `termsAgreedAt(now())`, `privacyAgreedAt(now())` 빌더 호출 제거
- [ ] **R4** — `UserMeResponse`: `terms_agreed_at`, `privacy_agreed_at`이 null인 경우 FE에서 "동의 대기" 표시 고려 (확인 필요)

### Wave 2 — consent DB 조회 캐싱 (M-P2)
- [ ] **R5** — `ConsentService`에 `Cache<Long, Boolean>` Caffeine 캐시 추가 (TTL 1시간, max 50,000)
- [ ] **R6** — `hasRequiredConsents(userId)`: 캐시 히트 시 DB 조회 생략
- [ ] **R7** — `recordSignupConsents(userId, ...)` 완료 후 캐시에 `userId → true` 워밍
- [ ] **R8** — `UserService.softDeleteMe(userId)` 또는 `AuthService.forceLogout(userId)` 호출 시 캐시 무효화

### Wave 3 — 동의 미완료 계정 배치 정리 (M-M2)
- [ ] **R9** — `OAuthIncompleteAccountCleanupJob` 신규 `@Scheduled` 배치 작성 (매일 새벽 3시)
- [ ] **R10** — 삭제 기준: `hasRequiredConsents()=false && created_at < (now() - 3일)` 인 OAuth 계정
- [ ] **R11** — 삭제 순서: `refresh_tokens` 먼저 삭제(CASCADE) → `users` soft delete(deleted_at 설정) 또는 hard delete
- [ ] **R12** — 배치 실행 결과 로그 기록 (삭제 건수, 스킵 건수)
- [ ] **R13** — 멀티 인스턴스 환경 대비 ShedLock 또는 DB 락 검토 (현재 단일 인스턴스이므로 MVP에서는 skip 가능)

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(user/, shared/)
- **영향 파일**:
  - `backend/src/main/resources/db/migration/V19__nullable_agreed_at.sql` — **신규**
  - `backend/.../user/entities/UserEntity.java` — `termsAgreedAt`, `privacyAgreedAt` nullable 변경
  - `backend/.../user/services/AuthService.java` — `autoSignup()` 빌더 필드 제거
  - `backend/.../user/services/ConsentService.java` — Caffeine 캐시 추가, `hasRequiredConsents()` 캐시 적용
  - `backend/.../user/services/UserService.java` — soft delete 시 캐시 무효화
  - `backend/.../user/services/OAuthIncompleteAccountCleanupJob.java` — **신규** `@Scheduled` 배치
- **DB 변경**: V19 마이그레이션 (ALTER TABLE users ALTER COLUMN terms_agreed_at DROP NOT NULL)
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- 기존 `@Scheduled` 패턴: `DisclosurePollingJob.java`(fixedDelay=60s), `NotificationRetryJob.java`, `StockMasterSyncJob.java`
- 스케줄링 설정: `SchedulingConfig.java`에 `@EnableScheduling` 이미 활성화
- Caffeine 수동 사용: `AuthService.java` OAuth state CSRF 캐시 (`Caffeine.newBuilder().expireAfterWrite(5, MINUTES).maximumSize(10_000)`)
- Spring Cache 자동 설정: `application.yml`에 `spring.cache.type: caffeine` 선언됨 — `@Cacheable` 어노테이션 방식 사용 가능
- 최신 Flyway 버전: V18 (2026-06-11) → 차기 V19 사용

## 리스크 / 법적 검토

- **정보통신망법 §29 (안전조치)**: 동의 미완료 계정이 3일 이상 개인정보(이메일, OAuth ID, placeholder 포함)를 보유하면 미사용 개인정보로 간주될 수 있음. 배치 정리가 법적 리스크 완화.
- **Flyway V19 후 기존 데이터**: 현재 `terms_agreed_at NOT NULL`이므로 기존 데이터는 모두 not-null. V19 적용 후 `autoSignup()` 경로 계정만 null을 가짐. 기존 이메일 가입 계정은 영향 없음.
- **캐시 일관성**: `hasRequiredConsents()` 캐시 TTL 1시간 중 계정 soft delete 시 캐시 무효화 필수. 미무효화 시 삭제된 계정에 대해 `true` 반환 → 보안 위험 없음(토큰도 삭제됨), 단 DB 불일치.
- **배치 삭제 범위**: OAuth 계정 중 동의 미완료만 삭제. 이메일 가입 계정은 `oauth_provider IS NOT NULL` 조건으로 제외됨.

## 권장 구현 방향

### Wave 1 (V19 마이그레이션 + UserEntity)

```sql
-- V19__nullable_agreed_at.sql
ALTER TABLE users ALTER COLUMN terms_agreed_at DROP NOT NULL;
ALTER TABLE users ALTER COLUMN privacy_agreed_at DROP NOT NULL;
COMMENT ON COLUMN users.terms_agreed_at IS '이메일 가입 시 동의 시각. OAuth 가입은 consent_logs가 SSOT — NULL 허용';
COMMENT ON COLUMN users.privacy_agreed_at IS '이메일 가입 시 동의 시각. OAuth 가입은 consent_logs가 SSOT — NULL 허용';
```

`ddl-auto: validate`이므로 `@Column(nullable = true)` 변경과 동시 적용 필수.

### Wave 2 (캐시)

`ConsentService`에 수동 Caffeine 캐시 추가 (AuthService의 oauthStateCache와 동일 패턴):

```java
private final Cache<Long, Boolean> consentCache = Caffeine.newBuilder()
    .expireAfterWrite(1, TimeUnit.HOURS)
    .maximumSize(50_000)
    .build();
```

`hasRequiredConsents()`에서 `consentCache.getIfPresent(userId)` → null이면 DB 조회 후 캐시 저장.

### Wave 3 (배치)

```java
@Component
public class OAuthIncompleteAccountCleanupJob {
    // @Scheduled(cron = "0 0 3 * * *") — 매일 새벽 3시
    // WHERE oauth_provider IS NOT NULL
    //   AND created_at < now() - interval '3 days'
    //   AND NOT EXISTS (SELECT 1 FROM consent_logs WHERE user_id = u.id)
    //   AND deleted_at IS NULL
}
```

삭제 시 `UserService.softDeleteMe()` 재사용 또는 전용 배치 삭제 쿼리(`@Modifying @Query`).

## Tech Review (dc-tech-review · 2026-06-23)

### ⚠️ 전제 재검증 — Spec 작성(2026-06-16) 이후 코드 변경 반영

본 Spec은 `oauth-consent-enforcement`(2026-06-23 Done) 구현 **이전**에 작성되어 일부 전제가 어긋났다. 코드 실측 결과:

| 이슈 | Spec 원전제 | 현재 코드(2026-06-23) | 판정 |
|------|------------|----------------------|------|
| **E3** | `autoSignup()`이 동의 전 `termsAgreedAt/privacyAgreedAt=now()` 설정 | `AuthService.java:288-289` **그대로 유지** | ✅ **유효** — 진행 |
| **M-P2** | "동의 완료자도 **매 로그인마다** `hasRequiredConsents()` DB 조회" | `oauthCallback`이 `onboarding_completed_at` 기준으로 전환(`AuthService.java:236-238`). `hasRequiredConsents()`는 **`POST /me/oauth-consent` 멱등 체크 1회**만 호출(`UserController.java:87`) | ❌ **전제 소멸** — **Drop**(사용자 승인 2026-06-23) |
| **M-M2** | 좀비 계정 삭제 기준 `hasRequiredConsents()=false` | `onboarding_completed_at IS NULL`이 더 정확한 미완료 지표(`AuthService.java:57` 주석이 이미 제시) | ⚠️ **유효 + 기준 변경** — `onboarding_completed_at IS NULL`(사용자 승인) |

> **마이그레이션 버전 정정**: Spec 본문은 "V19"를 제안하나, 실제 적용 마이그레이션은 **V18 다음이 V20**(`add_onboarding_completed_at`)으로 V19 슬롯이 비어있다. 지금 V19를 추가하면 Flyway out-of-order(기본 `out-of-order: false`) 충돌. **차기 = `V21`** 사용.

### 아키텍처 분해

- **영향 레이어**: backend(`user/` only). `shared/`·`infrastructure/`·외부 계약 무관.
- **신규**: `V21__nullable_agreed_at.sql`, `OAuthIncompleteAccountCleanupJob.java`
- **수정**: `UserEntity`(agreed_at nullable), `AuthService.autoSignup()`(빌더 필드 제거), `ConsentService`/`ConsentLogRepository`(stale 주석 정정)
- **폐기**: Wave 2 캐싱(R5~R8) — `ConsentService` Caffeine 캐시 미도입
- **비변경 확정**: `signup()`(이메일 가입은 동의와 동시 발생 — `now()` 정합 유지), `oauthCallback`(이미 `onboarding_completed_at` 사용), `UserController`(멱등 체크 유지)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| **Wave 1 — agreed_at 정합 (E3)** | | | | | |
| 1 | `V21__nullable_agreed_at.sql` 신규 — `ALTER TABLE users ALTER COLUMN terms_agreed_at DROP NOT NULL` + privacy 동일 + COMMENT(OAuth는 consent_logs가 SSOT) | backend/user(db) | BE | 하 | - |
| 2 | `UserEntity` — `termsAgreedAt`·`privacyAgreedAt` `@Column(nullable = true)`로 변경 (V21과 동시 적용, ddl-auto:validate 호환) | backend/user | BE | 하 | #1 |
| 3 | `AuthService.autoSignup()` — `.termsAgreedAt(now)`·`.privacyAgreedAt(now)` 빌더 호출 제거 (`:288-289`). OAuth 동의는 `consent_logs`가 SSOT | backend/user | BE | 하 | #2 |
| 4 | `UserMeResponse` OAuth 계정 `terms_agreed_at`·`privacy_agreed_at` null 직렬화 영향 — **확인 완료**: FE `AuthUser` 타입은 이미 `?`(optional)이며 UI 렌더링 없음. FE 변경 불필요. | frontend | FE | 하 | #3 ✅ |
| **Wave 2 — consent 캐싱 (M-P2)** | | | | | |
| ~~5~~ | ~~ConsentService Caffeine 캐시~~ → **Drop**(전제 소멸). 대신 `ConsentService:27`·`ConsentLogRepository:16` "OAuth 로그인마다 호출" stale 주석을 "POST /me/oauth-consent 멱등 체크 시 호출"로 정정 | backend/user | BE | 하 | - |
| **Wave 3 — 좀비 계정 정리 배치 (M-M2)** | | | | | |
| 6 | `OAuthIncompleteAccountCleanupJob` 신규 `@Scheduled(cron="0 0 3 * * *")` — 기존 `DisclosurePollingJob`/`NotificationRetryJob` 패턴 준수 | backend/user | BE | 중 | - |
| 7 | 삭제 대상 쿼리 — `oauth_provider IS NOT NULL AND onboarding_completed_at IS NULL AND created_at < now()-interval '3 days' AND deleted_at IS NULL` | backend/user | BE | 중 | #6 |
| 8 | 삭제 순서 — `refresh_tokens` 먼저(FK) → `users` **soft delete(`deleted_at` 설정) 확정**. hard delete는 `consent_logs` 보존 의무(통합기획서 §11.1) 위반이므로 금지. ✅ | backend/user | BE | 중 | #7 |
| 9 | 배치 결과 로그(삭제 건수·스킵 건수) + 멀티 인스턴스 ShedLock은 MVP skip(단일 인스턴스, 주석으로 명시) | backend/user | BE | 하 | #6 |

### DB / 마이그레이션 영향

- **신규**: `backend/src/main/resources/db/migration/V21__nullable_agreed_at.sql` (V19/V20 아님 — 위 정정 참조)
- `ALTER TABLE users ALTER COLUMN terms_agreed_at DROP NOT NULL` + `privacy_agreed_at` 동일. **적용 마이그레이션 불변 원칙**(CLAUDE.md §6-3) — V20 수정 금지, 신규 V21만.
- 기존 데이터: 현재 모두 not-null. V21 후 `autoSignup()` 경로 신규 계정만 null. 이메일 가입 계정 영향 없음.
- **Wave 3 삭제는 DDL 무변경** — DML(soft delete UPDATE 또는 DELETE)만.

### 외부 계약 영향

- **없음.** DART/KRX/카카오 알림톡/LLM 무관. OAuth provider(Kakao/Google/Naver) 응답 스키마 무변경.

### 리스크 & 법적 검토

- **정보통신망법 §29(안전조치)**: 동의 미완료 OAuth 계정 3일+ 보유 시 미사용 개인정보 리스크 → Wave 3 배치가 완화. **단 `consent_logs`는 INSERT-only 불변 이력(통합기획서 §11.1)** — 계정 hard delete 시 consent_logs도 함께 삭제되면 동의 거부 이력 소실. 카드 #8에서 soft delete 우선 권장하는 이유.
- **E3 데이터 정합**: `autoSignup` 계정의 `users.agreed_at`은 V21 후 null. 동의 SSOT는 `consent_logs` — FE가 `users.terms_agreed_at`을 직접 신뢰하지 않도록 카드 #4가 게이트.
- **회귀 리스크**: 이메일 가입(`signup()`)의 `agreed_at=now()`는 **변경 금지** — 이메일 가입은 동의와 동시 발생이라 정합. autoSignup만 손댐.
- **캐시 일관성**(M-P2 Drop 부수효과): 캐시 미도입으로 stale-cache 보안 리스크(삭제 계정에 true 반환) 자체가 사라짐 — Drop이 오히려 안전.
- **배치 동시성**: 단일 인스턴스 전제로 ShedLock skip. 수평 확장 시 `AuthService:66` oauthStateCache 주석과 동일하게 Redis/ShedLock 재검토 필요(카드 #9 주석).

### 예상 wave 수

- **2 wave**(M-P2 Drop으로 원안 3→2 축소):
  - **Wave 1** (E3): 카드 #1→#2→#3 순차 + #4(FE 확인). 1 PR.
  - **Wave 3** (M-M2): 카드 #6→#7→#8→#9. 1 PR. (배치는 독립적이라 Wave 1과 병렬 가능)
- 카드 ~~5~~(M-P2)는 주석 정정만 — Wave 1에 흡수 가능.

### 결정 완료 (2026-06-23)

1. **카드 #4 ✅** — FE `authStore.ts:27-28` 확인: `terms_agreed_at?`/`privacy_agreed_at?` 이미 optional 타입, UI 렌더링 없음. FE 변경 불필요. Wave 1 카드 #4는 확인 항목 제거.
2. **카드 #8 ✅** — **soft delete 확정**. `consent_logs`는 INSERT-only 불변 이력(통합기획서 §11.1) — hard delete 시 동의 이력 소실. soft delete(`deleted_at`)로 `users` 비활성화, `consent_logs` 보존.
