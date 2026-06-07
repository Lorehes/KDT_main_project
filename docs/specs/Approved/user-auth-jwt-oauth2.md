---
type: spec
status: Approved
created: 2026-06-07
updated: 2026-06-07
---

# User / Auth / Portfolio Spec (M2)

> 상태: Draft → **Approved** (2026-06-07, dc-tech-review 승인)

## 배경 / 목적

- **페르소나 A~F** 모두 사용자 계정이 있어야 분석 결과를 자신의 보유 종목과 연결할 수 있음
- Phase 1 MVP의 핵심 가치 명제(공시 알림 발송)는 **보유 종목 = 사용자 계정** 없이는 성립 불가
- 현재 user/ 모듈은 `package-info.java`만 있는 빈 껍질
- SecurityConfig는 `/admin/**` HTTP Basic 전용이며 "JWT 도입 시 교체"를 명시하고 있음
- BM 티어 (Free/Pro/Premium): **Free 3종목**, Pro/Premium 무제한 — 이번 Spec에서 Free 제한 구현
- `V1__create_users.sql` ~ `V9__create_feedbacks.sql`(+ V10~V13)는 이미 적용됨 — DB 스키마는 준비 완료

---

## 요구사항

### 인증 (Auth)
- [ ] 이메일 + 비밀번호 회원가입 (`POST /api/v1/auth/signup`)
  - 필수 동의 (TERMS · PRIVACY) 누락 시 `422 BUSINESS_RULE_VIOLATION`
  - 동의는 users.*_agreed_at + consent_logs INSERT 원자적 처리
  - 비밀번호 BCrypt 해시 저장 (평문 저장/로깅 금지)
- [ ] 이메일 로그인 → JWT 쌍 발급 (`POST /api/v1/auth/login`)
  - 응답: `{ access_token, refresh_token, token_type: "Bearer" }`
  - access_token: 30분 / refresh_token: 14일
- [ ] JWT 갱신 (`POST /api/v1/auth/refresh`)
  - refresh_token DB 조회 → 유효하면 새 access_token + 새 refresh_token 발급 (rotation)
- [ ] 로그아웃 (`POST /api/v1/auth/logout`)
  - refresh_token DB에서 삭제 (블랙리스트 방식이 아닌 삭제 방식)
  - access_token은 만료까지 유효 (MVP 허용 범위)
- [ ] OAuth2 인가 URL 발급 (`GET /api/v1/auth/oauth/{provider}/url`)
  - provider: `kakao` / `google` / `naver`
  - Naver는 state 파라미터 생성 + 캐시/세션 저장 (CSRF 방어)
- [ ] OAuth2 콜백 처리 (`POST /api/v1/auth/oauth/{provider}/callback`)
  - 인가 코드 → provider 토큰 교환 → 사용자 정보 조회
  - 기존 oauth_id 매칭 시 로그인, 없으면 자동 가입
  - 응답: 동일한 자체 JWT 쌍

### 사용자 (User)
- [ ] 내 프로필 조회 (`GET /api/v1/users/me`)
  - 응답: id, email, nickname, tier, tier_expires_at, phone_verified(bool), notify 설정
  - 휴대폰 번호는 평문 응답 금지 — `phone_verified` boolean만
- [ ] 닉네임 수정 (`PATCH /api/v1/users/me`)
- [ ] 회원 탈퇴 (`DELETE /api/v1/users/me`)
  - soft delete (`deleted_at` = now())
  - consent_logs는 ON DELETE RESTRICT → 보존

### 동의 (Consent)
- [ ] 동의 기록 INSERT (`POST /api/v1/consents`)
  - consent_logs INSERT-only (UPDATE/DELETE 금지)
- [ ] 최신 동의 상태 조회 (`GET /api/v1/consents/status`)
  - (user_id, consent_type) 기준 MAX(agreed_at) 조회

### 보유 종목 (Portfolio)
- [ ] 목록 조회 (`GET /api/v1/portfolios`)
  - avg_buy_price / quantity 복호화 후 응답 (평문 응답은 허용, 저장은 암호화)
- [ ] 등록 (`POST /api/v1/portfolios`)
  - Free: 3종목 초과 시 `422 BUSINESS_RULE_VIOLATION`
  - avg_buy_price / quantity AES-256-GCM 암호화 저장
  - 중복 `(user, stock_code)` → `409 DUPLICATE_RESOURCE`
- [ ] 수정 (`PATCH /api/v1/portfolios/{id}`)
  - 본인 소유 검증 (IDOR 방지)
- [ ] 삭제 (`DELETE /api/v1/portfolios/{id}`)
  - 본인 소유 검증

### 알림 설정 (Notification Settings)
- [ ] 조회 (`GET /api/v1/notifications/settings`)
- [ ] 갱신 (`PUT /api/v1/notifications/settings`)
  - 채널/빈도/필터/거래시간외 — users 테이블 V7 컬럼 갱신

### 종목 검색
- [ ] 종목 자동완성 (`GET /api/v1/stocks/search?q=삼성`) — PUBLIC

---

## 영향 범위

### 영향 레이어
- **Backend**: user/ (전체 신규) + shared/ (SecurityConfig 재작성, crypto 신규, security 신규) + infrastructure/ (OAuth 클라이언트 신규)

### 신규 파일 (user/)
```
user/
├── controllers/
│   ├── AuthController.java            # /api/v1/auth/**
│   ├── UserController.java            # /api/v1/users/me
│   ├── PortfolioController.java       # /api/v1/portfolios
│   ├── ConsentController.java         # /api/v1/consents
│   ├── StockSearchController.java     # /api/v1/stocks/search (PUBLIC)
│   └── NotificationSettingsController.java  # /api/v1/notifications/settings
├── services/
│   ├── AuthService.java               # signup, login, OAuth, refresh, logout
│   ├── UserService.java               # me, update, delete
│   ├── PortfolioService.java          # CRUD + tier 제한 + AES 암복호
│   ├── ConsentService.java            # INSERT-only, status 조회
│   └── NotificationSettingsService.java
├── repositories/
│   ├── UserRepository.java
│   ├── PortfolioRepository.java
│   ├── ConsentLogRepository.java
│   └── RefreshTokenRepository.java
├── entities/
│   ├── UserEntity.java                # users 테이블
│   ├── PortfolioEntity.java           # portfolios 테이블
│   ├── ConsentLogEntity.java          # consent_logs 테이블
│   └── RefreshTokenEntity.java        # refresh_tokens 테이블 (V14)
└── dto/
    ├── SignupRequest.java
    ├── LoginRequest.java
    ├── AuthResponse.java              # access_token, refresh_token
    ├── RefreshRequest.java
    ├── UserMeResponse.java
    ├── UpdateMeRequest.java
    ├── PortfolioCreateRequest.java
    ├── PortfolioUpdateRequest.java
    ├── PortfolioResponse.java
    ├── ConsentRequest.java            # consent_type, agreed, policy_version
    ├── ConsentStatusResponse.java
    ├── NotificationSettingsRequest.java
    └── NotificationSettingsResponse.java
```

### 수정 파일
```
shared/config/SecurityConfig.java      # 듀얼 체인 (admin HTTP Basic + user JWT Bearer)
backend/build.gradle                   # JWT + OAuth2 의존성 추가
backend/src/main/resources/application.yml  # JWT 설정, AES 키 프로퍼티
```

### 신규 파일 (shared/)
```
shared/security/
├── JwtTokenProvider.java              # 발급 / 파싱 / 검증
├── JwtAuthenticationFilter.java       # OncePerRequestFilter — Bearer 추출
└── UserDetailsServiceImpl.java        # UserRepository로 DB 조회
shared/crypto/
└── AesGcmEncryptor.java               # AES-256-GCM 암복호 (avg_buy_price, quantity, phone)
shared/config/
└── CryptoProperties.java              # @ConfigurationProperties — AES_KEY 바인딩
└── JwtProperties.java                 # @ConfigurationProperties — JWT_SECRET, TTL
```

### 신규 파일 (infrastructure/)
```
infrastructure/oauth/
├── KakaoOAuthClient.java              # token exchange + userinfo
├── GoogleOAuthClient.java
├── NaverOAuthClient.java
└── OAuthUserInfo.java                 # 공통 DTO (provider, oauth_id, email, nickname)
```

### DB 변경
- **V14__create_refresh_tokens.sql** — 신규 Flyway 마이그레이션 필요
  ```sql
  CREATE TABLE refresh_tokens (
      id          BIGINT      GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
      user_id     BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
      token_hash  VARCHAR(64) NOT NULL UNIQUE,    -- SHA-256(token)
      expires_at  TIMESTAMPTZ NOT NULL,
      created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
  );
  CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);
  CREATE INDEX idx_refresh_tokens_expires ON refresh_tokens (expires_at);
  ```
  > refresh_token 값 대신 SHA-256 해시만 저장 (토큰 탈취 시 DB 유출 최소화)

### 외부 계약
- **Kakao OAuth**: `kauth.kakao.com/oauth/authorize` (인가) + `kauth.kakao.com/oauth/token` (교환) + `kapi.kakao.com/v2/user/me` (사용자 정보)
- **Google OAuth**: `accounts.google.com/o/oauth2/v2/auth` + `oauth2.googleapis.com/token` + `www.googleapis.com/oauth2/v2/userinfo`
- **Naver OAuth**: `nid.naver.com/oauth2.0/authorize` + `nid.naver.com/oauth2.0/token` + `openapi.naver.com/v1/nid/me`
- **신규 환경변수**: `JWT_SECRET`, `JWT_ACCESS_TTL_MINUTES`, `JWT_REFRESH_TTL_DAYS`, `AES_KEY`, `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET`, `KAKAO_REDIRECT_URI`, `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `GOOGLE_REDIRECT_URI`, `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET`, `NAVER_REDIRECT_URI`

---

## 관련 패턴 / 과거 사례

- 기존 SecurityConfig (`shared/config/SecurityConfig.java`): HTTP Basic InMemoryUserDetailsManager → 듀얼 체인으로 교체. 주석에 "JWT 도입 시 교체" 명시됨
- BCryptPasswordEncoder: 이미 @Bean으로 등록 → 재사용
- `@ConfigurationProperties` 패턴: `AdminAuthProperties`(record) 기존 패턴 동일하게 `JwtProperties`, `CryptoProperties` 작성
- 외부 WebClient 클라이언트 패턴: `DartClient`, `KrxClient` 타임아웃·재시도 패턴 그대로 OAuth 클라이언트에 적용
- `@TransactionalEventListener(AFTER_COMMIT)` + `@Async`: 기존 분석 이벤트 패턴 — 알림 트리거용으로 AnalysisCompletedEvent가 이미 존재
- `SecretMasker` (`shared/util/`): 로그 마스킹 유틸 — 평문 로깅 방지에 활용

---

## 리스크 / 법적 검토

| 위험 | 대응 |
|------|------|
| avg_buy_price / quantity 평문 로깅 | AesGcmEncryptor 사용, SecretMasker 통해 로그 마스킹, @JsonIgnore |
| phone_number_enc 수집 | 알림톡 사용 시만 수집(선택). MVP에서 실제 SMS 인증 없이 컬럼만 예약(phone_verified=false 고정) |
| OAuth state CSRF (Naver) | state 생성 → 인메모리 캐시(Caffeine, TTL 5분) 저장 → 콜백 검증 후 삭제 |
| refresh_token 유출 | DB에 SHA-256 해시만 저장, HTTPS 필수, HttpOnly Cookie 권장(프론트 협의 필요) |
| 자본시장법: 투자 권유 표현 | 회원가입/프로필 화면은 해당 없음. 분석 결과 노출 시 disclaimer 필수 (별도 분석 Spec) |
| soft delete 후 개인정보 보존 | deleted_at 이후 PII 실제 삭제 스케줄(6개월 등) — MVP에서는 soft delete만, 스케줄 삭제는 후속 |
| 개인정보보호법: 이메일·닉네임 | 필수 최소 수집, consent_logs TERMS·PRIVACY 필수 동의 검증 |

---

## 권장 구현 방향

### SecurityConfig 듀얼 체인 (Spring Security 6)
```
@Order(1) AdminSecurityFilterChain  — /admin/**  HTTP Basic (기존 InMemoryUserDetailsManager 유지)
@Order(2) UserSecurityFilterChain   — /api/v1/** JWT Bearer + PUBLIC 허용 경로
```
두 체인 분리 이유: InMemoryUserDetailsManager와 DB UserDetailsService를 같은 체인에 두면 충돌.

### JWT 라이브러리 선택
`io.jsonwebtoken:jjwt-api:0.12.x` — Spring Boot 3.x 호환, 단순 API.
nimbus-jose-jwt는 oauth2-resource-server와 함께 사용 시 유리하지만 자체 발급 시 설정이 더 복잡.

### AES-256-GCM 암호화
```
IV (12 bytes) ‖ ciphertext ‖ GCM tag (16 bytes)
→ BYTEA 컬럼 저장 (db_schema §2.1 "암호화 컬럼 규약")
```
키: `AES_KEY` 환경변수 base64 디코딩 → 32바이트 SecretKey.

### OAuth2 수동 클라이언트 방식
api_spec이 "GET /auth/oauth/{provider}/url → 프론트 리다이렉트 → POST /auth/oauth/{provider}/callback" 방식.
Spring Security OAuth2 Client의 자동 redirect 방식이 아닌 **수동 WebClient 교환** 선택:
- 프론트엔드가 state를 직접 관리하는 SPA 구조에 적합
- 카카오/네이버는 표준 OIDC가 아니라 자체 API → 수동이 더 명확

### Free 3 포트폴리오 제한
DB 제약이 아닌 애플리케이션 계층:
```java
if (user.tier == FREE && portfolioRepo.countByUserId(userId) >= 3) {
    throw new BusinessRuleViolationException("FREE 등급은 3종목까지만 등록 가능");
}
```

### 구현 Wave 분해 (Tech Review용 힌트)
| Wave | 내용 | 우선순위 |
|------|------|--------|
| Wave 1 | 공유 인프라: JwtTokenProvider + AesGcmEncryptor + SecurityConfig 듀얼 체인 + UserDetailsServiceImpl + entities/repositories | 기반 |
| Wave 2 | 이메일 Auth: SignupService + LoginService + Refresh + Logout + AuthController + ConsentService | 핵심 |
| Wave 3 | 사용자 도메인: UserController(me/update/delete) + PortfolioService(CRUD + tier 제한) + PortfolioController + StockSearchController | 기능 |
| Wave 4 | OAuth2: KakaoOAuthClient + GoogleOAuthClient + NaverOAuthClient + AuthService OAuth 콜백 | OAuth |
| Wave 5 | 알림설정 API + 통합 테스트 (Testcontainers) | 완성 |

---

## 보안 체크 (구현 완료 기준)

- [ ] password_hash: BCrypt 저장, 평문 로깅 금지
- [ ] refresh_token: SHA-256 해시만 DB 저장, 만료 후 자동 정리 스케줄 고려
- [ ] avg_buy_price_enc / quantity_enc: AES-256-GCM 암호화 저장, 응답 시 복호화
- [ ] phone_number_enc: MVP에서 phone_verified=false 고정, 실 수집 시 AES 암호화 필수
- [ ] IDOR 방지: portfolios/{id} 조회 시 userId 소유자 검증 강제
- [ ] JWT 서명 키: JWT_SECRET 환경변수 (32바이트+), 코드/yml 하드코딩 금지
- [ ] AES 키: AES_KEY 환경변수, 코드/yml 하드코딩 금지
- [ ] OAuth state (Naver): Caffeine 캐시 5분 TTL, 콜백에서 검증 후 삭제
- [ ] 통합 테스트: Testcontainers PostgreSQL (Mock DB 금지)
- [ ] SecretMasker 적용: AuthService 로그에서 email/token 마스킹

---

## Tech Review (dc-tech-review · 2026-06-07)

### 아키텍처 분해

- **영향 레이어**: backend(user/ 전체 신규, shared/security·crypto·config 신규/수정, infrastructure/oauth 신규)
- **신규 클래스**: 40+ (entity 4 · repo 4 · service 6 · controller 6 · dto 13 · security 3 · crypto 1 · oauth 3 · properties 2)
- **수정 대상**: `shared/config/SecurityConfig.java` (듀얼 체인 재작성), `build.gradle` (jjwt 의존성), `application.yml` (JWT/AES/OAuth 설정)
- **DB**: V14 마이그레이션 1건 신규

### 작업 카드 (5 Waves · 18 Cards)

#### Wave 1 — 인프라 기반 (구현 의존성 최상위)

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `build.gradle` jjwt-api:0.12.x 추가 + `application.yml` jwt/aes/oauth 프로퍼티 키 추가 | backend | 하 | — |
| 2 | `V14__create_refresh_tokens.sql` Flyway 마이그레이션 | DB | 하 | — |
| 3 | `JwtProperties.java` + `CryptoProperties.java` (@ConfigurationProperties record, AdminAuthProperties 패턴) | shared/config | 하 | #1 |
| 4 | `JwtTokenProvider.java` — jjwt-api로 access/refresh 발급·파싱·만료검증 | shared/security | 중 | #1 #3 |
| 5 | `AesGcmEncryptor.java` — AES-256-GCM `IV(12)‖ciphertext‖tag(16)` → `byte[]` 암복호 | shared/crypto | 중 | #1 #3 |
| 6 | `UserEntity` + `PortfolioEntity` + `ConsentLogEntity` + `RefreshTokenEntity` + 각 Repository | user/entities + user/repositories | 하 | #2 |
| 7 | `UserDetailsServiceImpl.java` (UserRepository DB 조회) + `JwtAuthenticationFilter.java` (OncePerRequestFilter, Bearer 헤더 추출) | shared/security | 중 | #4 #6 |
| 8 | `SecurityConfig.java` 듀얼 체인 재작성 — `@Order(1)` AdminChain(HTTP Basic, /admin/**) + `@Order(2)` UserChain(JWT Filter, /api/v1/**) | shared/config | 중 | #7 |

> **듀얼 체인 주의**: 두 FilterChain이 동일 UserDetailsService를 공유하면 충돌. Admin 체인에는 `InMemoryUserDetailsManager`를, User 체인에는 `UserDetailsServiceImpl`을 각 `AuthenticationManager`에 별도 바인딩해야 함.

#### Wave 2 — 이메일 Auth

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 9 | `ConsentService.java` — INSERT-only consent_logs 기록, (user_id, consent_type) MAX(agreed_at) 조회 | user/services | 하 | #6 |
| 10 | `AuthService.java` — signup(BCrypt+consent 원자 처리) + login + refreshToken(rotation) + logout(DB 삭제) | user/services | 중 | #4 #6 #9 |
| 11 | `AuthController.java` + DTO 일괄 (SignupRequest · LoginRequest · AuthResponse · RefreshRequest) | user/controllers + dto | 중 | #10 |

> **refresh rotation**: 갱신 시 기존 refresh_token 삭제 + 신규 발급 (재사용 방지). token_hash 컬럼에 SHA-256(raw token) 저장.

#### Wave 3 — 사용자·포트폴리오

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 12 | `UserService.java` + `UserController.java` + DTO (UserMeResponse · UpdateMeRequest) — me/update/soft-delete | user/services + controllers | 하 | #6 |
| 13 | `PortfolioService.java` (CRUD + Free 3종목 제한 + AES 암복호) + `PortfolioController.java` + DTO | user/services + controllers | 중 | #5 #6 |
| 14 | `StockSearchController.java` (/api/v1/stocks/search, PUBLIC, stocks read-only 의존 허용) + `NotificationSettingsService` + `NotificationSettingsController` | user/controllers | 하 | #6 |

> **IDOR 방지**: Portfolio 조회·수정·삭제 시 `entity.getUserId().equals(currentUserId)` 검증 필수. 실패 시 `403 FORBIDDEN`.
> **Free 제한**: `portfolioRepo.countByUserId()` 결과가 3 이상이면 `422 BUSINESS_RULE_VIOLATION`. DB 제약이 아닌 서비스 레이어 검증.

#### Wave 4 — OAuth2 클라이언트

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 15 | `KakaoOAuthClient` + `GoogleOAuthClient` + `NaverOAuthClient` (RestClient + @Retryable, DartClient 패턴) + `OAuthUserInfo` 공통 DTO | infrastructure/oauth | 중 | #1 |
| 16 | `AuthService` OAuth 콜백 추가 (oauth_id 매칭 → 로그인/자동가입) + `AuthController` OAuth 엔드포인트 (GET /auth/oauth/{provider}/url · POST /auth/oauth/{provider}/callback) | user | 중 | #10 #15 |

> **Naver state CSRF**: `state` UUID 생성 → Caffeine 캐시(TTL 5분) 저장 → 콜백에서 일치 검증 후 삭제. 이미 `CacheManager`(Caffeine) Bean 등록됨 — 캐시 이름 `"oauthState"` 추가만 하면 됨.

#### Wave 5 — 통합 테스트

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 17 | `AuthIntegrationTest` (Testcontainers PG) — signup/login/refresh rotation/logout/중복이메일 409 | backend/test | 중 | #11 |
| 18 | `PortfolioIntegrationTest` — CRUD + Free 3종목 422 + AES 암호화 DB 저장 검증 + IDOR 403 | backend/test | 중 | #13 |

### DB / 마이그레이션 영향

| 구분 | 내용 |
|------|------|
| **기존** (이미 적용) | V1 users · V3 portfolios · V7 알림설정 · V8 consent_logs — 스키마 준비 완료 |
| **신규** | `V14__create_refresh_tokens.sql` — token_hash(VARCHAR 64, UNIQUE) · user_id(FK CASCADE) · expires_at · created_at |
| 인덱스 | `idx_refresh_tokens_user(user_id)` · `idx_refresh_tokens_expires(expires_at)` — 만료 토큰 배치 정리용 |

### 외부 계약 영향

| 항목 | 영향 |
|------|------|
| DART/KRX/LLM | **없음** — 공시·분석 파이프라인 무관 |
| Kakao OAuth | `kauth.kakao.com` 토큰 교환 + `kapi.kakao.com/v2/user/me` 사용자 정보 |
| Google OAuth | `oauth2.googleapis.com/token` + `www.googleapis.com/oauth2/v2/userinfo` |
| Naver OAuth | `nid.naver.com/oauth2.0/token` + `openapi.naver.com/v1/nid/me` |
| **신규 환경변수** | `JWT_SECRET` · `JWT_ACCESS_TTL_MINUTES` · `JWT_REFRESH_TTL_DAYS` · `AES_KEY` · `KAKAO_*` · `GOOGLE_*` · `NAVER_*` (총 11개) |

### 리스크 & 법적 검토

| # | 리스크 | 대응 |
|---|--------|------|
| R1 | AES_KEY 환경변수 누락 시 암호화 실패 부팅 | `@Validated` + `@NotBlank` → 부팅 시 즉시 실패 (안전 실패) |
| R2 | avg_buy_price / quantity 평문 로그 유출 | `SecretMasker` 적용 + DTO `@JsonProperty` 응답 후 내부 로그에서 마스킹 |
| R3 | Naver state CSRF 누락 | state UUID → Caffeine 캐시 5분 → 콜백 검증 후 삭제 (강제 게이트) |
| R4 | refresh_token 탈취 후 재사용 | Rotation (갱신 시 기존 삭제 + 신규 발급) + DB에 해시만 저장 |
| R5 | 소프트 딜리트 후 이메일 재가입 충돌 | `UserRepository.findByEmailAndDeletedAtIsNull()` 조회 — 탈퇴 계정 이메일로 재가입 허용 여부 정책 결정 필요 (구현 시 명시) |
| R6 | Free 3종목 초과 — 동시 요청 경쟁 조건 | `@Transactional` + `countByUserId()` + DB UNIQUE 제약(이미 존재)으로 이중 방어 |
| R7 | 개인정보: 이메일/닉네임 최소 수집 | 가입 시 TERMS·PRIVACY 필수 동의 검증 (422 강제), consent_logs INSERT |
| R8 | OAuth 미등록 provider 호출 | `{provider}` path variable → switch/enum 매핑, 미지원 값 `400 VALIDATION_ERROR` |

### 예상 Wave 수

**5 Waves · 18 Cards**
- Wave 1 (기반·인프라): Cards 1-8 — 병렬 구현 가능 (#3·#4·#5는 독립, #6·#7·#8은 순차)
- Wave 2 (이메일 Auth): Cards 9-11 — Wave 1 완료 후
- Wave 3 (사용자·포트폴리오): Cards 12-14 — Wave 2와 병행 가능
- Wave 4 (OAuth2): Cards 15-16 — Wave 2 완료 후
- Wave 5 (테스트): Cards 17-18 — Wave 3·4 완료 후

> **총 예상 작업량**: Wave 1~4 약 3~4일, Wave 5 약 1일 → **합계 4~5일**
