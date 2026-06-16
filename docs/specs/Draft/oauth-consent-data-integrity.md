---
type: spec
status: Draft
created: 2026-06-16
updated: 2026-06-16
---

# OAuth 동의 데이터 정합성 개선 Spec

> 상태: **Draft** (dc-plan 생성)

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

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
