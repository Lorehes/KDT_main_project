---
type: spec
status: Done
created: 2026-06-09
updated: 2026-06-10
---

# 보안 강화 MVP Spec (CORS · IDOR · CSP · 감사로그)

> 상태: **Done** (2026-06-10, 구현 완료 — R1~R14 전체, 테스트 120건 통과)

## 배경 / 목적

`/dc-review-code` 5축 멀티 페르소나 리뷰에서 **운영 배포 불가 수준의 보안 P0 4건 + P1 5건**이 확인됐다. 본 Spec은 MVP 출시 전 차단해야 할 보안 결함을 일괄 해소한다.

- **현황**: CORS 빈 미등록, 공시 상세/분석 엔드포인트 IDOR, 피드백 IDOR + 분석 은폐 공격, OAuth Open Redirect, CSP 헤더 부재, Swagger 무인증 노출, JWT 예외 로그 묵살, Feedback reason TEXT 무제한
- **목표**: OWASP Top 10 기준 인증·인가·노출 결함을 MVP 출시 전 제거
- **BM 연관**: 전 티어 공통 — IDOR 노출 시 Pro/Premium 응답 차등 우회

---

## 요구사항

### 인가 · IDOR

- [ ] **R1** `DisclosureController.detail()` · `analysis()` 에 `@AuthenticationPrincipal Long userId` 추가. 서비스에서 해당 공시의 `stockCode` 가 사용자 포트폴리오에 속하는지 검증 (또는 정책상 전체 공개 명시 결정 후 주석화)
- [ ] **R2** `AnalysisController` 의 `POST /analyses/{id}/feedback` 에서 `analysisId` 소유권 검증 — `analysisId` 의 `disclosureId` → `stockCode` → 사용자 포트폴리오 매칭. 미매칭 시 404 (정보 누설 방지)
- [ ] **R3** `FeedbackService` 에 동일 userId 시간당 N건 rate-limit 추가 (Caffeine 기반 토큰 버킷 또는 Bucket4j). 대량 INACCURATE 자동화 공격 차단
- [ ] **R4** `scope=all` 파라미터에 티어 제한 정책 결정 — Free 사용자가 전체 공시 피드를 열람 가능한지 BM 검토. 제한 시 `@PreAuthorize` 또는 서비스 가드

### 전송 · 노출

- [ ] **R5** `SecurityConfig` 에 `CorsConfigurationSource` 빈 등록 — 허용 origin 을 `ALLOWED_ORIGINS` 환경변수로 주입. `allowCredentials(true)` + 와일드카드 동시 사용 금지
- [ ] **R6** `next.config.ts` headers() 에 Content-Security-Policy 추가 — `default-src 'self'; script-src 'self'; connect-src 'self' ${API_ORIGIN}; img-src 'self' data:; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'`
- [ ] **R7** `SecurityConfig` Swagger UI (`/swagger-ui/**`, `/v3/api-docs/**`) 접근 제한 — `springdoc.api-docs.enabled` 기본값 false + 개발 프로파일 only true, 또는 ROLE_ADMIN 인가
- [ ] **R8** `frontend/src/lib/api/auth.ts` `getOAuthUrl()` 반환 URL 화이트리스트 검증 — `accounts.kakao.com`, `accounts.google.com`, `nid.naver.com` 외 거부 (Open Redirect 방지)

### 입력 · DoS

- [ ] **R9** `DisclosureController.list()` `size` 파라미터에 `@Max(100)` 추가 + 서비스 `Math.min(size, 100)` 이중 방어
- [ ] **R10** `AnalysisResultRepository.findByDisclosureIdIn()` 호출 시 IN 절 크기 상한 (서비스에서 size cap 100 적용 후 별도 partition 불필요)

### 감사 · 데이터 보호

- [ ] **R11** `JwtAuthenticationFilter` catch 블록에 WARN 로그 추가 — `log.warn("[JWT] Invalid token: {} path={}", e.getMessage(), request.getRequestURI())` (토큰 원본 값 미포함)
- [ ] **R12** `FeedbackEntity.reason` 컬럼을 `VARCHAR(2000)` 으로 명시적 제한 — `V18__alter_feedbacks_reason_length.sql` 마이그레이션 추가. `FeedbackEntity.update()` 내부에서도 길이 가드
- [ ] **R13** `FeedbackService.upsert()` 에 `DataIntegrityViolationException` catch 추가 — TOCTOU 동시 제출 시 409 Conflict 또는 멱등 처리(이미 존재 시 update 재시도)
- [ ] **R14** `application.yml` 운영 가이드에 `JWT_SECRET` 생성 명령 명시 — `openssl rand -base64 32`. `JwtProperties` 검증을 Base64 디코딩 후 ≥32바이트로 변경

---

## 영향 범위

- **영향 레이어**: backend (`shared/config`, `shared/security`, `disclosure`, `analysis`) + frontend (`lib/api`, `next.config.ts`)
- **DB 변경**: `V18__alter_feedbacks_reason_length.sql` (TEXT → VARCHAR(2000))
- **외부 계약**: CORS 화이트리스트 정책 + OAuth provider 도메인 화이트리스트 (FE 검증)

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../shared/config/SecurityConfig.java` | R5 CORS 빈 + R7 Swagger 가드 |
| `backend/.../shared/security/JwtAuthenticationFilter.java` | R11 WARN 로그 추가 |
| `backend/.../shared/config/JwtProperties.java` | R14 Base64 검증 |
| `backend/.../shared/security/JwtTokenProvider.java` | R14 Base64 디코딩 키 사용 |
| `backend/.../disclosure/controllers/DisclosureController.java` | R1 userId 주입 + R9 size 검증 |
| `backend/.../disclosure/services/DisclosureQueryService.java` | R1 소유권 검증 + R4 scope=all 가드 |
| `backend/.../analysis/controllers/AnalysisController.java` | R2 userId 주입 |
| `backend/.../analysis/services/FeedbackService.java` | R2 소유권 검증 + R3 rate-limit + R13 TOCTOU catch |
| `backend/.../analysis/entities/FeedbackEntity.java` | R12 길이 가드 |
| `backend/src/main/resources/db/migration/V18__alter_feedbacks_reason_length.sql` (신규) | R12 |
| `frontend/next.config.ts` | R6 CSP 헤더 |
| `frontend/src/lib/api/auth.ts` | R8 OAuth URL 화이트리스트 |

---

## 관련 패턴 / 과거 사례

- `user-auth-jwt-oauth2` (Done) — Spring Security 필터 체인, JWT 발급/검증
- 2026-06-02 `disclosure-collection-pipeline` 리뷰 결과 — `HostWhitelist` 패턴(SSRF 방어) 동일 화이트리스트 검증 구조 재사용
- 2026-06-02 admin auth gate — `/admin/**` Basic Auth 적용 선례 (Swagger 가드에 동일 패턴 적용 가능)
- CLAUDE.md §7 — DART/LLM API 키 하드코딩 금지, 금융 개인정보 평문 로깅 금지 (Feedback rate-limit 로그도 PII 미포함)

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| CORS 화이트리스트 누락된 도메인에서 FE 요청 실패 | 환경별 ENV 관리 — 로컬/스테이징/프로덕션 분리. 변경 시 CHANGELOG 의무화 |
| 공시 상세 IDOR 차단으로 기존 데모/공유 기능 영향 | scope=all 정책 결정과 연동 — 공개 가능 분류는 별도 엔드포인트로 분리 검토 |
| Swagger 비활성화로 외부 협업 API 문서 접근 곤란 | 정적 스냅샷(openapi.json) 별도 생성 + 사내 위키 호스팅 |
| `JWT_SECRET` Base64 검증 강화로 기존 secret 무효화 | 마이그레이션 가이드: 신규 secret 발급 + 사용자 전체 강제 재로그인 1회 공지 |
| rate-limit 정상 사용자 차단 가능성 | 시간당 30건 시작 → 운영 데이터로 조정. 차단 시 429 Retry-After 반환 |
| Feedback 길이 제한 강화 후 기존 데이터 손실 | V18 마이그레이션 전 TEXT → VARCHAR(2000) truncate 사전 안내 + 백업 |

---

## 권장 구현 방향

- Wave 1 (인가 차단): R1·R2·R3·R4 — IDOR 부터 우선 차단
- Wave 2 (전송 보호): R5·R6·R7·R8 — CORS/CSP/Swagger/Open Redirect
- Wave 3 (입력/감사): R9·R10·R11·R12·R13·R14 — DoS 가드 + 로그/마이그레이션
- 각 Wave 마다 `dc-test-verify` 통합 테스트 + `dc-review-code --scope security` 회귀
- [[fe-auth-token-refresh-flow-rewrite]] 와 인터페이스 정렬 — 본 Spec 의 CSP 가 토큰 흐름 재설계와 충돌하지 않도록 `connect-src` 도메인 명시

## Tech Review (dc-tech-review · 2026-06-10)

### 아키텍처 분해

- **영향 레이어**: backend (`shared/config` · `shared/security` · `disclosure` · `analysis` · `user`) + frontend (`next.config.ts` · `lib/api/auth.ts`)
- **신규**: `V16__alter_feedbacks_reason_length.sql`, `SecurityUtils.extractTier()` (shared/security로 이관)
- **수정**: `SecurityConfig`, `JwtAuthenticationFilter`, `JwtProperties`, `JwtTokenProvider`, `DisclosureController`, `DisclosureQueryService`, `AnalysisQueryService`, `FeedbackService`, `FeedbackEntity`, `next.config.ts`, `lib/api/auth.ts`
- **정책 결정 (R1)**: 공시 상세(`GET /disclosures/{id}`)는 DART 공개 데이터이므로 **인증만으로 공개** (주석에 명시). 분석 결과(`GET /disclosures/{id}/analysis`)는 유료 데이터이므로 **portfolio stockCode 소유권 검증** 적용. → `DisclosureController.analysis()`에 `@AuthenticationPrincipal Long userId` 추가, `AnalysisQueryService.getByDisclosureId()` userId 파라미터 추가.
- **정책 결정 (R4)**: `scope=all`은 Pro+(PRO/PREMIUM) 전용 — Free는 portfolio 3종목 한정이 BM 차별화 포인트. `DisclosureQueryService.list()`에서 `scope=all && tier==FREE` 시 403 반환.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `DisclosureController.analysis()`에 `@AuthenticationPrincipal Long userId` 추가 + `AnalysisQueryService.getByDisclosureId(disclosureId, tier, userId)` 시그니처 변경. 소유권 검증: `disclosureId → stockCode → portfolioRepository.existsByUserIdAndStockCode()`. 미소유 시 404 (정보 누설 방지). 공시 상세(`detail()`)는 공개 정책 주석만 추가 (R1) | backend/disclosure + backend/analysis | BE | 중 | - |
| 2 | `FeedbackService.upsert()`에 소유권 검증 추가 — `analysisId → analysisResult.disclosureId → disclosure.stockCode → portfolio(userId)`. 미소유 시 404 반환 (R2) | backend/analysis | BE | 중 | #1 (PortfolioRepository 의존 패턴 참고) |
| 3 | `FeedbackService`에 Caffeine 기반 userId 시간당 rate-limit 추가. `LoadingCache<Long, AtomicInteger>` 또는 `Caffeine.newBuilder().expireAfterWrite(1, HOURS)`로 count 관리. 한도 초과 시 429. 초기 한도 30건/시간 (R3) | backend/analysis | BE | 중 | - |
| 4 | `DisclosureQueryService.list()` — `scope=all && tier == FREE` 시 `ResponseStatusException(FORBIDDEN, "scope=all은 Pro 이상 플랜에서 사용 가능합니다")` 반환. `list()` 메서드 시그니처에 `UserEntity.Tier tier` 추가, `DisclosureController.list()`에서 `extractTier(authentication)` 전달 (R4) | backend/disclosure | BE | 하 | - |
| 5 | `SecurityConfig`에 `CorsConfigurationSource` 빈 등록. `ALLOWED_ORIGINS` 환경변수(콤마 구분) 파싱 → `setAllowedOrigins()`. `allowCredentials(true)` + `allowedHeaders("*")` + `allowedMethods("GET","POST","PUT","DELETE","OPTIONS","PATCH")`. `http.cors(c -> c.configurationSource(source))` 활성화. 환경변수 미설정 시 빈 목록 → CORS 전면 차단 (안전 실패) (R5) | backend/shared/config | BE | 중 | - |
| 6 | `next.config.ts` `headers()`에 CSP 헤더 추가. `default-src 'self'; script-src 'self'; connect-src 'self' ${process.env.NEXT_PUBLIC_API_URL ?? ''} wss:; img-src 'self' data: https:; style-src 'self' 'unsafe-inline'; font-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'`. `fe-auth-token-refresh-flow-rewrite`의 토큰 흐름(httpOnly 쿠키)과 충돌 없는 구조 확인 (R6) | frontend | FE | 하 | - |
| 7 | `SecurityConfig` — Swagger/OpenAPI 접근 제한. `springdoc.api-docs.enabled`/`springdoc.swagger-ui.enabled` 기본값 `false`, `application-local.yml`에서만 `true`. SecurityConfig에서 `/v3/api-docs/**`, `/swagger-ui/**` → `.permitAll()` 제거 후 `hasRole("ADMIN")` 또는 profile 조건부로 교체 (R7) | backend/shared/config | BE | 하 | - |
| 8 | `frontend/src/lib/api/auth.ts` `getOAuthUrl()` — 반환 URL이 `accounts.kakao.com`, `accounts.google.com`, `nid.naver.com` 중 하나로 시작하는지 검증. 미일치 시 `throw new Error("OAuth URL이 허용되지 않은 도메인입니다")` (R8) | frontend | FE | 하 | - |
| 9 | `DisclosureController.list()` `size` 파라미터에 `@Max(100)` 추가 (`@RequestParam @Max(100) @Positive ...`). `DisclosureQueryService.list()`에서 `Math.min(size, 100)` 이중 방어. Jakarta Validation `@Validated` 컨트롤러 레벨 애노테이션 확인 (R9·R10) | backend/disclosure | BE | 하 | - |
| 10 | `JwtAuthenticationFilter` catch 블록에 WARN 로그 추가. `log.warn("[JWT] Invalid token: {} path={}", e.getMessage(), request.getRequestURI())`. 토큰 원본 값·userId는 로그에 미포함. `private static final Logger log = LoggerFactory.getLogger(...)` 추가 (R11) | backend/shared/security | BE | 하 | - |
| 11 | `V16__alter_feedbacks_reason_length.sql` — `UPDATE feedbacks SET reason = LEFT(reason, 2000) WHERE LENGTH(reason) > 2000` 후 `ALTER TABLE feedbacks ALTER COLUMN reason TYPE VARCHAR(2000)`. `FeedbackEntity.update(verdict, reason)` 내부 + builder에서 `reason` 길이 2000자 가드 추가 (R12) | backend/analysis + DB | BE | 하 | - |
| 12 | `FeedbackService.upsert()` 마지막 `save()` 호출을 `try { ... } catch (DataIntegrityViolationException e)` 로 감싸 TOCTOU 동시 삽입 충돌 처리. 409 Conflict 또는 멱등 처리(findByUserIdAndAnalysisId 재조회 후 update) (R13) | backend/analysis | BE | 중 | - |
| 13 | `JwtProperties` `@Size(min=32)` → Base64 검증 커스텀 어노테이션 또는 `@PostConstruct` 검증. `JwtTokenProvider` 생성자에서 `Base64.getDecoder().decode(props.secret())` 후 키 길이 ≥32 bytes 어사션. 운영 가이드(`application.yml` 주석): `openssl rand -base64 32` 명령 안내. **파괴적 변경** — 기존 `JWT_SECRET`은 Base64가 아니면 부팅 실패. 마이그레이션 가이드: 신규 secret 발급 + 기존 access token 전부 무효화(사용자 재로그인 1회) (R14) | backend/shared | BE | 상 | 독립 (다른 Wave 완료 후 마지막) |
| 14 | 통합 테스트 추가 — CORS preflight 200 확인, scope=all FREE 403, Feedback IDOR 404, size=99999 → 400, JWT 위변조 → 401 + WARN 로그 확인 (R7 Swagger 비활성 → 403) | backend/test | BE | 중 | #1~#12 |

### DB / 마이그레이션 영향

- **V16__alter_feedbacks_reason_length.sql** (신규)
  - `UPDATE feedbacks SET reason = LEFT(reason, 2000) WHERE LENGTH(reason) > 2000;` (사전 truncate)
  - `ALTER TABLE feedbacks ALTER COLUMN reason TYPE VARCHAR(2000);`
  - 기존 `TEXT` 컬럼이 가진 값 > 2000자인 경우 사전 truncate 필수 (없으면 `ALTER` 실패)
- **인덱스 변경**: 없음

### 외부 계약 영향

- **CORS 화이트리스트**: FE 배포 도메인(`NEXT_PUBLIC_SITE_URL`)을 `ALLOWED_ORIGINS` 환경변수로 세팅 필요. 로컬 개발 시 `http://localhost:3000` 포함.
- **OAuth provider 도메인**: 카카오 `accounts.kakao.com`, 구글 `accounts.google.com`, 네이버 `nid.naver.com` — FE 화이트리스트 고정값으로 코드에 명시.
- **DART/KRX/LLM/카카오알림톡**: 변경 없음.

### 리스크 & 법적 검토

| 리스크 | 영역 | 대응 |
|--------|------|------|
| R14 JWT_SECRET Base64 변경 → 기존 secret 무효화, 모든 사용자 강제 재로그인 | 기술·UX | 배포 전 신규 secret 생성(`openssl rand -base64 32`) + 사용자 공지. 마이그레이션 가이드 작성 |
| R4 scope=all Pro+ 제한 → 기존 Free 사용자 사용 불가 | BM | spec 결정이므로 FE UX에 업그레이드 유도 메시지 추가 필요 (fe-correctness-investor-protection 연계) |
| R5 CORS 화이트리스트 미등록 도메인 → FE 요청 실패 | 기술 | 배포 환경별 `ALLOWED_ORIGINS` 변수 검증 가이드 제공 |
| Feedback IDOR 차단(R2) → 기존 사용 중인 API 응답 변화 | 기술 | 운영 데이터 없는 MVP 단계이므로 영향 없음 |
| JWT WARN 로그(R11) — 공격 로그 폭주 시 디스크 포화 | 운영 | log.warn 레벨 + 로그 회전 정책 확인. rate-limit 로깅은 별도 고려 |
| FeedbackEntity reason VARCHAR(2000) 기존 데이터 truncate | 데이터 | V16 migrate 전 백업 권장. MVP 단계이므로 실질 데이터 없음 |
| 자본시장법 §11.1 — R1 analysis IDOR 차단으로 무단 분석 조회 방지 | 법적 | 분석 결과(Pro/Premium 유료 서비스)는 포트폴리오 소유권 검증 필수. FREE 사용자가 타인 Premium 분석 우회 차단. |

### 예상 wave 수

- **Wave 1** (인가·IDOR): #1·#2·#3·#4 — P0 보안 차단, BE 전용
- **Wave 2** (전송·노출): #5·#6·#7·#8 — CORS/CSP/Swagger/OAuth, BE + FE
- **Wave 3** (입력·감사·DB): #9·#10·#11·#12·#13·#14 — DoS가드·로그·V16·R13·R14(고위험)
- 각 Wave 후 `dc-test-verify` + `dc-review-code --scope security`
- R14(JWT Base64)는 Wave 3 마지막 독립 카드로 분리, 배포 전 운영 가이드 동반 필수
