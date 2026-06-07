---
type: worklog
status: active
created: 2026-06-02
updated: 2026-06-02
---

# WORKLOG

> 세션 단위 작업 기록. dc-push가 자동 갱신. dc-handoff의 데이터 소스.

---

## 2026-06-02 | stocks 도메인 도입 + disclosure N+1 해소

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 2 핵심)

### 완료
- `stocks` 도메인 신규 도입 (`Stock` 엔티티 V2 매핑, `StockRepository`)
- `StockRepository.findAllStockCodes()` — 배치당 1회 Set 로드로 deferred HIGH N+1 해소
- `KrxApiProperties` 스켈레톤 + `application.yml` `dartcommons.krx.*`
- `DisclosureCollectionService` — JdbcTemplate 제거, StockRepository 의존, `collectSingle` 시그니처 변경(coveredCodes Set 파라미터)
- `DisclosureCollectionIntegrationTest` — fixture를 `stockRepository.save()`로 전환 (6/6 통과)
- `review-findings-deferred.md` — N+1 항목 제거

### 결정 (코드에 드러나지 않는 사항)
- **아키텍처 예외 (CLAUDE.md §3-2)**: 마스터 데이터 도메인(`stocks/`)은 다른 도메인이 read-only로 직접 의존 허용. write는 마스터 도메인 내부 한정. 옵션 B(shared facade)는 indirection만 늘려 기각.
- **market 필드**: String 유지 (enum 미사용). V10 시드/SyncJob과 동시 도입 예정.
- **KRX 키 발급 완료**, 출처: KRX 공개 CSV (data.krx.co.kr)

### 미완료 (다음 세션)
stocks-master-seed Wave 1 + 3년치 백필 가능 상태:
- `#1` KRX 실측 (엔드포인트/파라미터 확정) + `api_spec.md` §3.2 갱신
- `#3` `DartCorpCodeClient` (corpCode.xml zip 다운로드 + 파싱)
- `#4` `KrxClient` (RestClient + 종목 기본정보)
- `#5` `scripts/data_collection/seed_stocks.py` (KRX 공개 CSV + DART 매핑 → V10 SQL 출력)
- `#7` `V10__seed_stocks.sql` (스크립트 결과 → 적용)
- `#8` `StockMasterService` (upsert 오케스트레이터)
- `#9` `StockMasterSyncJob` (@Scheduled 분기 1회)
- **백필 진입점**: `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill` (3년치 수집 가능해짐)
- **DART 윈도우 청크 분할**: 3개월 단위 + 속도 제어
- **DB 청크 적재 최적화**: 건별 save → saveAll 배치

### deferred 잔여 (review-findings-deferred.md)
- HIGH: `Thread.sleep` 블로킹 재시도 (spring-retry 의존성 추가 결정 후)
- HIGH: `disclosure` → `infrastructure` DTO 직접 의존 (아키텍처 리팩 Spec)
- MEDIUM: `lastPolledDate` 인메모리 (멀티 인스턴스 전환 시점)
- MEDIUM: SSRF 화이트리스트 부재
- LOW: API 키 에러 로그 노출

---

## 2026-06-02 | Wave 1 + 3: stocks 시드 파이프라인 + 동기화 잡

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (Wave 1 #3/#4/#5 + Wave 3 #8/#9)

### 완료
- `scripts/data_collection/seed_stocks.py` — pykrx로 KOSPI200/KOSDAQ150 인덱스 구성 + DART corpCode 매핑 → V10 SQL 자동 생성 (`ON CONFLICT DO UPDATE` 멱등)
- `scripts/data_collection/requirements.txt` (pykrx, requests) + `README.md` 실행 가이드
- `DartCorpCodeClient` — corpCode.xml zip 다운로드 + StAX 스트리밍 파싱(메모리 효율) + zip 매직넘버 검사로 JSON 에러 응답 분기
- `KrxClient` skeleton — Spec 카드 #1 실측 placeholder, `fetchAllBasicInfo()`는 빈 Map 반환 + WARN 로그
- `StockMasterService` — DART corp_code + KRX 기본정보 결합해 기존 stocks 행 upsert (신규 편입 종목 자동 추가 안함 — V10/별도 정책 분리, 편입 제외 종목 삭제 안함 — portfolios FK 보호)
- `StockMasterSyncJob` — `@Scheduled(cron = "0 0 4 1 1,4,7,10 *" KST)` 분기 1회, 예외 비중단
- 통합 테스트 7/7 통과 (DartcommonsApplicationTests + DisclosureCollectionIntegrationTest 6건)

### 결정 (코드에 드러나지 않는 사항)
- **V10 시드 SQL은 코드에 미포함** — 스크립트 실행 산출물(`seed_stocks.py` 산출). 사용자가 1회 실행 → SQL 생성 → 커밋 흐름.
- **분기 SyncJob은 기존 행 갱신만** — 신규 편입은 V10 마이그레이션(또는 후속 분기 V{n}), 편입 제외 삭제는 후속 Spec(사용자 알림 + portfolios FK 마이그레이션).
- **KrxClient placeholder**: #1 실측 미완료. 빈 Map 반환 + WARN 로그. SyncJob 호출되더라도 DART corp_code만 갱신, sector 무변화. #1 실측 후 KrxApiProperties endpoint 필드 추가하고 placeholder만 교체하면 됨(컴파일 영향 최소화).
- DART corpCode 응답 zip 매직넘버(`0x50 0x4B`) 검사로 JSON 에러 응답(키 오류 등) 빠르게 분기.

### 미완료 (다음 세션)
**남은 카드**:
- `#1` KRX 정확한 엔드포인트 실측 + `api_spec.md` §3.2 갱신 (네트워크 호출 필요)
- `#7` 사용자가 `seed_stocks.py` 실행 → V10 SQL 생성 → 커밋
- `#12` `feature_structure.md` §4 / `api_spec.md` §3.2 / `db_schema.md` 갱신

**3년치 백필 진입점 (별도 Spec 권장)**:
- `BackfillRunner` 또는 REST `POST /admin/disclosures/backfill?from=&to=`
- DART 윈도우 3개월 청크 분할 + DB saveAll 배치
- `Thread.sleep` 블로킹 재시도 → `spring-retry` 도입 (deferred HIGH 동시 해결)

---

## 2026-06-02 | spring-retry + BackfillService + KrxClient 실구현

**Spec**: `docs/specs/Approved/stocks-master-seed.md` (#1 KRX 부분) + 신규 백필 경로

### 완료
- **spring-retry 도입** (build.gradle: `spring-retry` + `spring-aspects`, `SchedulingConfig`에 `@EnableRetry`)
- **`DartClient` 리팩토링** — `Thread.sleep` 제거. 페이지 호출은 `DartPageFetcher` 내부 `@Component`로 분리(AOP 프록시 제약 회피) + `@Retryable(maxAttempts=3, exponential backoff)`. **deferred HIGH 해결**
- **`KrxClient` 실구현** — pykrx 검증 엔드포인트(`data.krx.co.kr/comm/bldAttendant/getJsonData.cmd`, bld=`MDCSTAT01901`) POST 폼 호출 + JSON `OutBlock_1` 파싱. `@Retryable`. 검증 미완료 명시(비공식 API).
- **`DisclosureBackfillService`** — 90일 청크 분할(`WINDOW_DAYS=90`), 청크별 트랜잭션, saveAll 배치(`CHUNK_SIZE=500`), 이벤트 발행 옵션(기본 false — 백필 시 analysis 큐잉 폭주 방지)
- **`DisclosureBackfillController`** — `POST /admin/disclosures/backfill?from=&to=&emitEvents=false`
- **`application.yml`** — `hibernate.jdbc.batch_size=500` + `order_inserts/updates=true`
- **`api_spec.md` §3.2 갱신** — KRX 공개 데이터 API 패턴 + 검증 필요 마커. DART list.json 표에 백필 라인 추가
- **`DisclosureBackfillServiceTest`** — 청크 분할 / 커버 필터 / 500+ 배치 / 멱등 (4건 통과)
- 전체 테스트 **11/11 통과** (Application + Disclosure 6 + Backfill 4)

### 결정 (코드에 드러나지 않는 사항)
- **백필 경로는 이벤트 미발행 기본** — 3년치 65k+ 건이 분석 도메인 큐로 쏟아지면 폭주. 백필 후 analysis는 별도 배치로 트리거 권장.
- **`DartPageFetcher` 내부 클래스 분리** — `@Retryable`은 AOP 프록시 기반이라 same-class self-invocation 우회. 별도 `@Component`로 분리해 정상 작동.
- **`KrxClient` 패턴은 pykrx 기반 추정** — KRX `data.krx.co.kr`는 비공식 인터페이스라 사전 공지 없이 변경 가능. 운영 환경에서 실측 1회 권장(통합 테스트 또는 curl). 시드 산출은 pykrx에 위임(seed_stocks.py)해 안정성 확보.
- **`BackfillController` 인증 미적용** — Spring Security 미도입 상태. 운영 배포 전 `@PreAuthorize("hasRole('ADMIN')")` 필수.

### 운영 사용 흐름
```bash
# 1. stocks 시드
cd scripts/data_collection && python seed_stocks.py
git add backend/src/main/resources/db/migration/V10__seed_stocks.sql && git commit -m "..."
./gradlew bootRun

# 2. 3년치 백필 (별도 터미널)
curl -X POST "http://localhost:8080/admin/disclosures/backfill?from=2023-06-01&to=2026-06-01&emitEvents=false"
# → 13개 청크 × 평균 ~5k건 = ~65k건 적재, DART rate limit 여유
```

### 미완료
- KRX 운영 실측 (사용자 환경에서 1회 검증 후 `KrxClient.parseResponse()` 필드명 조정)
- `BackfillController` 인증 가드 (Spring Security 도입 시점)
- 비동기 백필 (현재 동기 — 큰 범위는 HTTP 타임아웃 위험)
- KRX 일별 시세 + Stage 3 5일 반응 (Spec 분리)

---

## 2026-06-02 | deferred 전건 해결 + 보안 게이트

**Spec**: 모든 deferred 항목 마무리 (`review-findings-deferred.md` status: open → resolved)

### 완료
- **LOW**: `SecretMasker` 유틸 + 외부 클라이언트 예외 메시지 마스킹 (`crtfc_key=...` → `***`)
- **MEDIUM**: `HostWhitelist` 유틸 + `DartClient`/`DartCorpCodeClient`/`KrxClient` 생성자에서 `baseUrl` 호스트 검증 (prod: `opendart.fss.or.kr`, `data.krx.co.kr` / test: localhost)
- **MEDIUM**: V11 `system_configs` 마이그레이션 + `SystemConfig` 엔티티/`SystemConfigRepository`. `DisclosurePollingJob` `AtomicReference` → DB 영속화 + 재기동 복원
- **HIGH**: `disclosure/dto/RawDisclosureItem` 도메인 DTO 도입. `DartClient.fetchList()` 반환 타입 변경, `DisclosureCollectionService`/`DisclosureBackfillService`/`DisclosurePollingJob` 모두 `RawDisclosureItem`만 의존. 변환 책임은 infrastructure(`DartClient.toDomain`)
- **보안 게이트**: `spring-boot-starter-security` + `SecurityConfig`(HTTP Basic `/admin/**` 인증). `AdminAuthProperties`(env 주입). in-memory user. `AdminAuthIntegrationTest` 3건(401·401·200) 통과
- 통합 테스트 **14/14 통과** (Application 1 + Disclosure 6 + Backfill 4 + AdminAuth 3)

### 결정 (코드에 드러나지 않는 사항)
- **DTO 리팩토링 범위**: `DartListResponse.Item`은 infrastructure 내부에 그대로 보존(역직렬화용). 도메인이 가져가는 형태만 `RawDisclosureItem`으로 분리. infrastructure가 변환 책임.
- **SSRF 화이트리스트**: profile-gated 대신 단일 화이트리스트 + test 호스트 함께 보유 — 단순함 우선. 운영에서 test 호스트 시도 시 부팅 실패는 의도된 빠른 실패.
- **`system_configs`는 shared/config에 위치**: 다른 도메인 잡 상태도 공유 가능. `config_key` 네이밍 컨벤션 `<domain>.<key>` 권장.
- **Spring Security MVP**: in-memory user 1명(`admin`) + HTTP Basic + STATELESS. JWT는 user 도메인 도입 시점에 교체. `/admin/**` 외에는 permitAll 유지(공시 통역 사용자 라우트와 충돌 없음).
- **`ADMIN_PASSWORD` 미설정 시**: Spring placeholder 미해결로 그대로 문자열 binding → @NotBlank 통과 → 인증은 항상 실패(literal 비교). 운영에선 강한 무작위 env 주입 필수.

### 운영 사용 흐름 (변경)
```bash
# 환경변수 추가
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD=<strong-random-32+chars>

# 백필 호출 — HTTP Basic 인증 필수
curl -X POST -u admin:<password> \
  "http://localhost:8080/admin/disclosures/backfill?from=2023-06-01&to=2026-06-01&emitEvents=false"
```

### 미완료 (다음 작업)
- KRX 운영 실측 (사용자 환경에서 1회 검증 후 `KrxClient.parseResponse()` 필드명 조정)
- KRX 일별 시세 + Stage 3 5일 반응 (별도 Spec)
- user 도메인 + JWT (`/admin/**` Basic 가드 교체) — 별도 Spec
- analysis 도메인 (Stage 2~5 LLM 분석) — 별도 Spec, `DisclosureCollectedEvent` 소비자

---

## 2026-06-02 | 비동기 백필 잡

**Spec**: (없음 — 단일 PR 단순 작업)

### 완료
- **V12 `backfill_jobs`** 마이그레이션 (status CHECK enum, chunks_total/done, fetched/saved, timestamps, error_message)
- **`BackfillJob` 엔티티** + `BackfillJobRepository` (UUID jobId 외부 키)
- **`BackfillJobService`** — `createJob()` PENDING 생성, `@Async runAsync()` 별도 스레드 실행, `Propagation.REQUIRES_NEW`로 청크당 진행률 갱신
- **`DisclosureBackfillService`** — `ChunkProgressListener` 콜백 추가 (오버로드로 기존 시그니처 유지), `calculateChunks()` 정적 메서드
- **컨트롤러 비동기 엔드포인트** — `POST /admin/disclosures/backfill/jobs` 202 + jobId, `GET .../jobs/{id}` 상태 조회 (NotFound 404)
- **`SchedulingConfig`에 `@EnableAsync`** 추가
- 통합 테스트 **21/21 통과** (기존 14 + BackfillJobService 3 + BackfillJobController 4)

### 결정 (코드에 드러나지 않는 사항)
- **TaskExecutor 빈 미설정** — Spring 기본 `SimpleAsyncTaskExecutor`(매 호출 새 스레드). 운영 부하 클 경우 `ThreadPoolTaskExecutor` 빈 추가로 동시 잡 수 제한.
- **잡 cleanup/중단 미구현** — 후속 작업 (운영 정책 결정 후).
- **동시 백필 중복 방지 없음** — 같은 범위 두 번 호출 시 두 잡 생성. `rcept_no` 멱등으로 데이터 안전성은 보장.
- **`runAsync` 호출은 컨트롤러에서 직접** — `createJob` 끝나자마자 트리거. PENDING→RUNNING 사이에 짧은 갭 존재(허용 범위).

### 운영 사용 흐름 (3년치 백필 비동기)
```bash
# 1. 잡 생성 — 즉시 202 + jobId
curl -X POST -u admin:<password> \
  "http://localhost:8080/admin/disclosures/backfill/jobs?from=2023-06-01&to=2026-06-01&emitEvents=false"
# → {"jobId":"...", "status":"PENDING", ...}

# 2. 진행률 조회 — 5분마다 폴링
curl -u admin:<password> \
  "http://localhost:8080/admin/disclosures/backfill/jobs/<jobId>"
# → {"status":"RUNNING", "chunksDone":7, "chunksTotal":13, "saved":35000, ...}
```

---

## 2026-06-07 | M2 user-auth-jwt-oauth2 Wave 1 — JWT + AES-256 + 사용자 도메인 인프라

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 1)

### 완료
- **build.gradle** — jjwt-api 0.12.6 (impl/jackson runtimeOnly) 추가
- **application.yml** — `dartcommons.jwt.*` / `dartcommons.crypto.*` 프로퍼티 추가
- **V14** `refresh_tokens` 마이그레이션 — `token_hash VARCHAR(64)` SHA-256 only, ON DELETE CASCADE
- **JwtProperties** + **CryptoProperties** — `@ConfigurationProperties` record + `@Validated` (미설정 시 부팅 즉시 실패)
- **JwtTokenProvider** — access token(HMAC-SHA256), raw refresh token(SecureRandom base64 48B), hashRefreshToken(SHA-256 hex), parseClaims
- **AesGcmEncryptor** — AES-256-GCM, IV 12B SecureRandom 매 호출 신규, `GeneralSecurityException` catch, null-safe
- **UserEntity** (OAuthProvider/Tier/NotifyChannel/NotifyFrequency/NotifyTypeFilter enum, soft delete, V1+V7 매핑)
- **PortfolioEntity** (avgBuyPriceEnc/quantityEnc BYTEA AES-256)
- **ConsentLogEntity** (ConsentType enum, INSERT-only)
- **RefreshTokenEntity** (token_hash SHA-256 전용, isExpired())
- **4 Repository** — UserRepository(soft-delete 쿼리), PortfolioRepository(userId 스코프+stock_code 역조회), ConsentLogRepository(findLatestByUserIdAndType), RefreshTokenRepository(deleteExpiredTokens @Transactional)
- **UserDetailsServiceImpl** — Spring Security 확장 포인트 (현재 미사용, form login 대비)
- **JwtAuthenticationFilter** — Bearer 토큰 추출 → 서명 검증 → SecurityContext principal=userId(Long). DB 조회 없음.
- **SecurityConfig 듀얼 체인** — @Order(1) /admin/** HTTP Basic, @Order(2) JWT Bearer + **401 AuthenticationEntryPoint** 추가(미인증→401, 미인가→403 구분)
- **src/test/resources/application.yml** — 테스트 전용 더미 JWT/AES 값
- 리뷰 수정 4건: SecurityConfig 401 EP, RefreshTokenRepository @Transactional, JwtProperties @Size(min=32), AesGcmEncryptor GeneralSecurityException

### 결정 (코드에 드러나지 않는 사항)
- **refresh token = SHA-256 해시만 DB 저장** — raw 토큰 미보관. DB 유출 시 원본 재사용 불가.
- **AES-GCM IV = 매 암호화 신규 생성** — 동일 평문도 매번 다른 결과(DB 정렬 불가 — 복호화 후 앱 계층에서만 연산).
- **듀얼 FilterChain 선택** — admin HTTP Basic과 user JWT를 같은 체인에 두면 Spring Security 6.4+에서 충돌. securityMatcher("/admin/**")로 격리.
- **UserDetailsServiceImpl 위치(shared/security)** — Spring Security 인프라가 user 레포에 의존. CLAUDE.md §3-2 역방향 금지 예외로 허용(Security 인프라 관행).
- **AES_KEY 관리** — 분실 시 기존 암호화 데이터 복호화 불가. 프로덕션은 AWS KMS/Vault DEK 패턴 권장.

### 미완료 (Wave 2)
- **AuthService** — signup(BCrypt+consent), login(access+refresh 발급), refresh(rotation), logout(hash 삭제), force-logout(deleteByUserId)
- **ConsentService** — 동의 이력 INSERT, 최신 동의 상태 조회
- **AuthController** — `POST /api/v1/auth/signup`, `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`
- **DTO** — SignupRequest(@Valid), LoginRequest, TokenResponse(accessToken+refreshToken+expiresIn), RefreshRequest
- **만료 토큰 정리 스케줄러** — `@Scheduled` + `deleteExpiredTokens()`

---

## 2026-06-03 | 운영 환경 부팅 + 백필 완료 + OTHER 룰 보강

**산출**:
- **운영 배포**: docker-compose 포트 5433 분리, `.env` 자동 로드(build.gradle bootRun task), `application.yml` 기본값 5433, `docker-compose.yml`에 `${DB_PASSWORD}` 매핑
- **첫 부팅**: Flyway V1~V12 자동 적용 + V10 시드로 stocks 341건
- **3년치 백필**: `POST /admin/disclosures/backfill/jobs` 비동기 진입점 사용 — 13 청크 × 3.7시간 → **fetched 756,410건, saved 91,965건**
- **자동 폴링 검증**: `@Scheduled` 1분 폴링이 신규 공시 실시간 적재 중
- **DisclosureTypeClassifier 룰 16종 보강**: OTHER 61%(56,054건) → 8%(7,302건). **87% 회수**
- `scripts/data_collection/reclassify_other.sql` 일괄 재분류 적용

### 결정 (코드에 안 드러나는 사항)
- **포트 분리**: 다른 프로젝트(`gc-postgres` 5432) 충돌 회피. DartCommons는 5433 고정.
- **백필 비동기 채택**: 동기 호출은 3.7시간 → HTTP 타임아웃 위험. `@Async` + jobId + 진행률 영속화.
- **OTHER 8% 잔여는 의도적 비유지**: M1 Stage 2 LLM이 OTHER도 처리 예정 → 룰 보강 비용 대비 가치 낮음. diminishing returns.
- **분류 결과 분포**: EXECUTIVE_SHARE 15.5k가 1위 (임원·주요주주 거래 정상). 증권사 5개가 종목 TOP 5 (운용/매매 공시 빈번).

### 다음 세션 가장 큰 가치 — M1 Stage 2 LLM
9.2만 건 데이터에 호재/악재 라벨 부여. `/dc-plan analysis-stage2-llm`부터 시작.
