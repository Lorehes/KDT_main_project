---
type: worklog
status: active
created: 2026-06-02
updated: 2026-06-08
---

# WORKLOG

> 세션 단위 작업 기록. dc-push가 자동 갱신. dc-handoff의 데이터 소스.

---

## 2026-06-08 | M2 user-auth Wave 5 — 통합 테스트 + 기존 테스트 수정

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 5 완료)

### 완료
- `AuthIntegrationTest` (6 테스트): signup 201, 중복 409, login 200, 잘못된 비밀번호 401, refresh rotation + 기존 토큰 401, logout 204 + 토큰 무효화
- `PortfolioIntegrationTest` (8 테스트): CRUD 201/200/204, Free 3종목 초과 422, IDOR GET/DELETE 403, AES-256-GCM DB 바이트 직접 검증
- 기존 테스트 31건 수정: `dartcommons.admin.password=test-admin-password` + `dartcommons.llm.provider=mock` 누락 해소 (8개 테스트 클래스)
- `src/test/resources/application.yml`에 admin/llm/dart/krx 공통 기본값 추가 → 신규 테스트 자동 정상화
- 전체 58/58 테스트 통과 (Testcontainers PostgreSQL)

### 결정 (코드에 드러나지 않는 사항)
- **test yml 기본값 전략**: `dartcommons.admin.password`, `dartcommons.llm.provider=mock`을 `@TestPropertySource`마다 반복하는 대신 `src/test/resources/application.yml`에 한 번만 정의. `@TestPropertySource`로 덮어쓰는 테스트는 override 방식 유지.
- **MockLlmClient 활성 조건**: `dartcommons.llm.provider=mock` 명시 필수(`matchIfMissing=false`). test yml의 `${LLM_PROVIDER:mock}` fallback은 Spring 환경 우선순위 상 무시됨 — 명시 프로퍼티가 필수.
- **Wave 5 종료**: Spec `user-auth-jwt-oauth2` → `/dc-spec-move user-auth-jwt-oauth2 Done` 필요

### 미완료
- `/dc-spec-move user-auth-jwt-oauth2 Done` — Spec 상태 Approved → Done 전환

---

## 2026-06-08 | M3 notification-retry-job Wave 1+2 — RetryJob + ChannelSender 분리 + 통합 테스트

**Spec**: `docs/specs/Approved/notification-retry-job.md` (Wave 1+2 완료)

### 완료
- `V15__add_notification_message.sql` — `message_body TEXT`, `message_subject VARCHAR(200)` 추가. RetryJob이 Disclosure·AnalysisResult 재조회 없이 재발송.
- `NotificationEntity` — messageBody/messageSubject 필드 + `storeMessage()`. markRetrying() 삭제(JPQL이 단독 소유).
- `ChannelSender` (신규) — Dispatcher·RetryJob 공유 발송 로직 단일 진실 소스. sendKakao/sendEmail/markUnsupported 이관.
- `NotificationDispatcher` 수정 — ChannelSender 주입. 일시적 채널 오류 시 FAILED 기록 대신 PENDING 유지 → RetryJob 처리.
- `NotificationRepository` — `findRetryTargets` JPQL(Pageable 100건 제한), `markAsRetrying` CAS UPDATE(`@Modifying(clearAutomatically=true)`).
- `NotificationRetryService` (신규) — `@Transactional` self-invocation 해결 위해 retryOne 분리. MAX_RETRY=3, RETRY_STATUSES 공개 상수.
- `NotificationRetryJob` — `@Scheduled(fixedDelay=300s)`, BATCH_SIZE=100, 건별 try-catch, `@ConditionalOnProperty`.
- `NotificationRetryJobIntegrationTest` — 10 케이스: PENDING→SENT, RETRYING→SENT, retryCount<MAX→RETRYING유지, MAX-1→FAILED확정, pre-V15 null→FAILED, already-SENT→skip, 배치3건, findRetryTargets FAILED/MAX_RETRY제외, soft-delete유저→FAILED.
- dc-review-code 리뷰 게이트: Critical 2건·High 3건·Medium 2건 해결 후 Green.
- 79/79 테스트 통과 (기준선 69 → 79).

### 결정 (코드에 드러나지 않는 사항)
- **Dispatcher 일시적 오류 전략**: `channelSender.send()` 예외 시 FAILED 기록 제거 → record PENDING 유지. RetryJob이 5분 이내 PENDING 픽업. 영구 실패(전화번호 없음, TELEGRAM 미지원)는 ChannelSender 내부 `markFailed()` + save() 처리 — Dispatcher catch 불도달.
- **self-invocation 패턴**: `NotificationRetryJob.retryFailedNotifications()` → `this.retryOne()` 호출 시 Spring 프록시 우회 → `@Transactional` 무효. NotificationRetryService를 별도 빈으로 분리해 프록시 경유 보장.
- **ChannelSender 비대칭 계약 허용**: 영구 실패(복호화 오류, blank phone)는 내부 markFailed + 정상 반환. 일시적 실패(API throw)는 전파. 호출자가 일시적/영구 구분 불가 문제는 Wave 4+ 타입 예외 계층(TransientChannelException) 도입으로 해소 예정.
- **notification-retry-job Spec Done 전환 가능**: Wave 1+2 완료 → `/dc-spec-move notification-retry-job Done` 필요.

### 미완료
- `/dc-spec-move notification-retry-job Done` — Spec 상태 Approved → Done 전환
- `/dc-spec-move user-auth-jwt-oauth2 Done` — Wave 2 이월
- **Sentiment → shared 이관**: cross-domain tech debt (notification→analysis 직접 참조)
- **TransientChannelException 계층**: ChannelSender 발송 실패 타입 분류 (일시적 vs 영구) — Wave 4+ 도입
- **ShedLock**: 다중 인스턴스 배포 시 RetryJob 분산 락 (MVP 단일 인스턴스 허용)
- **MAX_RETRY 외부화**: `application.yml` `dartcommons.notification.max-retry` 프로퍼티

---

## 2026-06-08 | M3 notification-dispatcher Wave 3 — 통합 테스트

**Spec**: `docs/specs/Approved/notification-dispatcher.md` (Wave 3 완료)

### 완료
- `NotificationDispatcherIntegrationTest` — 11개 통합 테스트 (Testcontainers PostgreSQL + MockitoBean 채널 클라이언트)
  - INSTANT 4단계 필터 전 경로: withheld / notifyEnabled / typeFilter(POSITIVE_ONLY·NEGATIVE_ONLY) / frequency(DAILY_1)
  - dedup: 동일 (user, disclosure, channel) 2회 이벤트 → DB 1건 기록 확인
  - 채널 라우팅: KAKAO 전화번호 있음(SENT) / 없음(FAILED) / EMAIL(SENT)
  - 신뢰도 낮음(confidence<0.5) → '판단 보류' 메시지 포함 확인
  - 포트폴리오 미보유 공시 → 발송 없음 확인
- dc-review-code Green (A+ 종합, Critical 0 / High 0 / Medium 0)
  - Medium 2건 수정: Thread.sleep(700) → Awaitility during(500ms)/atMost(2s), nanoTime → UUID substring

### 결정 (코드에 드러나지 않는 사항)
- **부정 테스트 Awaitility 패턴**: `during(500ms).atMost(2s).until(isEmpty)` — 500ms 동안 지속적으로 비어있음을 검증. CI 환경 Thread.sleep flaky 방지.
- **Wave 3 범위 확정**: off_hours_allowed 필터 테스트는 TradingHoursUtil Clock 주입 도입 후 별도 Wave. TELEGRAM 채널 테스트는 실 구현 후 추가.
- **M3 Wave 3 완료** — notification-dispatcher Spec Wave 1~3 완료.

### 미완료
- **RetryJob**: PENDING/RETRYING 상태 재발송 배치 (sent_at IS NULL 이중 발송 방지)
- **Sentiment → shared 이관**: cross-domain 의존 tech debt 해소
- **카카오 실계정 endpoint 검증**: 실계정 승인 후 SEND_PATH + AlimtalkRequest 필드 검증
- `/dc-spec-move user-auth-jwt-oauth2 Done` — Wave 2 미완료 이월

---

## 2026-06-08 | M3 notification-dispatcher Wave 2 — 디스패처 코어

**Spec**: `docs/specs/Approved/notification-dispatcher.md` (Wave 2 완료)

### 완료
- `NotificationEntity` — notifications(V6) JPA 엔티티. Channel/Status enum, markSent/markFailed, 500자 truncate
- `NotificationRepository` — `findByUserId` + `findByStatus` (idx_notifications_status 부분 인덱스 활용)
- `NotificationMessageBuilder` — 채널 무관 본문/제목 조립. confidence<0.5 "판단 보류" 삽입, 면책문구 고정
- `NotificationDispatcher` — `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("notificationExecutor")`. 4단계 INSTANT 필터(enabled→type_filter→off_hours→INSTANT) + 채널 라우팅(KAKAO/EMAIL/TELEGRAM). uq_notification_dedup DataIntegrityViolationException 멱등 처리. 유저별 try-catch 격리.
- dc-review-code Green (Critical 0, High 0 / A- 종합)

### 결정 (코드에 드러나지 않는 사항)
- **DataIntegrityViolationException dedup 패턴**: SimpleJpaRepository.save()는 자체 TX. 위반 예외 catch 후 skip이 caller TX 오염 없이 안전. 각 save()는 독립 TX.
- **cross-domain 한시 허용**: `notification` → `disclosure.entities.Disclosure` + `analysis.entities.AnalysisResult.Sentiment` 직접 참조. CLAUDE.md §3-2 위반이나 MVP 한시 허용(주석 명시). Sentiment → shared 이관 + Disclosure 요약 → event payload로 해소 예정.
- **N+1 쿼리 MVP 허용**: 보유자별 userRepository 단건 조회. 인기 종목 대량 보유자 시 개선 필요 — Wave 3 RetryJob 설계 시 `findAllById()` 일괄 조회로 전환 검토.
- **발송 후 상태 업데이트 실패**: 발송 성공 후 markSent save() 실패 시 record가 PENDING 잔류. RetryJob은 PENDING 재발송 전 sent_at IS NULL 체크로 이중 발송 방지 필요.
- **TELEGRAM**: MVP 미지원 → FAILED 상태로 기록. 후속 Spec에서 텔레그램 채널 추가 시 markUnsupported 분기 제거.

### 미완료
- **Wave 3**: `NotificationDispatcherIntegrationTest` (Testcontainers PostgreSQL, MockBean KakaoAlimtalkClient + MailNotificationClient). 4단계 필터 단위 테스트 포함.
- **RetryJob**: Wave 3+ PENDING/RETRYING 상태 재발송 배치 (sent_at IS NULL 체크로 이중 발송 방지)
- 카카오 알림톡 실계정 승인 후 endpoint/request body 검증 필요

---

## 2026-06-08 | M3 notification-dispatcher Wave 1 — 알림 인프라 기반

**Spec**: `docs/specs/Approved/notification-dispatcher.md` (Wave 1 완료)

### 완료
- `build.gradle` — `spring-boot-starter-mail` 추가
- `KakaoAlimtalkProperties` + `KakaoAlimtalkClient` (RestClient + `@Retryable`, HostWhitelist SSRF 방어)
- `MailNotificationProperties` + `MailNotificationClient` (JavaMailSender 래퍼, `MailSendException`, `isDebugEnabled()` guard)
- `TradingHoursUtil` — KRX 09:00~15:30 KST 판단 (`shared/util/`, private 상수)
- `ExecutorConfig.notificationExecutor` 빈 추가 (core=2, max=4, queue=500)
- `HostWhitelist.PROD_ALLOWED` — `alimtalk-api.kakao.com` 추가
- `application.yml` — `dartcommons.kakao.alimtalk.*` + `spring.mail.*` + `dartcommons.mail.from` 추가
- `test/resources/application.yml` — Kakao/mail 더미값 추가, `.env` import **제거** (보안)
- dc-review-code Green 통과 (NPE guard, private record, MailSendException, 이메일 마스킹 일관화 수정 포함)

### 결정 (코드에 드러나지 않는 사항)
- **카카오 알림톡 endpoint 미검증 허용**: `SEND_PATH="/v1/message/send"` + `AlimtalkRequest` 필드명은 "확인 필요" 상태로 Wave 1 머지. 카카오 비즈메시지 계정 승인 전 실검증 불가 — Wave 2에서 실계정 확인 후 확정.
- **test yml .env import 제거**: 로컬 `.env`의 실 운영 키가 테스트 더미값을 덮어쓰는 보안 위험 제거. bootRun의 `.env` 로드(build.gradle Gradle task)는 영향 없음.
- **ExecutorConfig 거절 정책**: `ThreadPoolTaskExecutor` 기본은 AbortPolicy (CallerRunsPolicy 아님). Wave 2 NotificationDispatcher 구현 시 이벤트 리스너 스레드 블로킹 방지를 위해 명시적 CallerRunsPolicy 추가 검토.
- **maxRetries property 미반영**: `KakaoAlimtalkProperties.maxRetries`가 `@Retryable(maxAttempts=3)` 리터럴로 하드코딩됨. DartClient와 동일 패턴 — RetryTemplate 전환 시 해결.

### 미완료
- **Wave 2**: `NotificationEntity` + `NotificationRepository` + `NotificationMessageBuilder` + `NotificationDispatcher` (`@TransactionalEventListener(AFTER_COMMIT)` + `@Async` + INSTANT 4단계 필터 + 채널 라우팅)
- **Wave 3**: `NotificationDispatcherIntegrationTest` (Testcontainers, MockBean 채널 클라이언트)
- 카카오 알림톡 실계정 승인 후 endpoint/request body 검증 필요

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

## 2026-06-07 | M2 user-auth Wave 4 — OAuth 2.0 Kakao/Google/Naver

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 4: Cards 15-16)

### 완료
- **OAuthProperties** — @ConfigurationProperties("dartcommons.oauth") record. kakao/google/naver 3중첩 record. application.yml 환경변수 섹션 추가
- **OAuthProviderClient** (interface) — getProviderName / buildAuthorizationUrl / getUserInfo(code, state)
- **KakaoOAuthClient** — kauth.kakao.com 토큰 + kapi.kakao.com/v2/user/me. RestClient(timeout 5s/10s) + @Retryable(noRetryFor=4xx)
- **GoogleOAuthClient** — oauth2.googleapis.com/token + googleapis.com/userinfo. 동일 패턴
- **NaverOAuthClient** — nid.naver.com/oauth2.0/token(state 포함) + openapi.naver.com/v1/nid/me. 동일 패턴
- **AuthService** OAuth 메서드 추가: getOAuthAuthorizationUrl(state CSRF Caffeine TTL 5min) + oauthCallback(state검증→getUserInfo→oauth_id 조회→로그인/자동가입)
- **AuthController** OAuth 엔드포인트: `GET /api/v1/auth/oauth/{provider}/url` + `POST /api/v1/auth/oauth/{provider}/callback`
- **DTO 2종**: OAuthCallbackRequest(@NotBlank code+state), OAuthUrlResponse(url+state)
- dc-review-code **Green** (HIGH 1건 수정: concurrent signup DataIntegrityViolationException→409, MEDIUM 2건: timeout 추가, toOAuthProvider valueOf 단순화)

### 결정
- **state CSRF 저장소**: Caffeine 캐시(in-process) 선택. Redis 불필요(단일 인스턴스 MVP). 수평 확장 시 Redis 전환 필요.
- **OAuth 이메일 충돌 정책**: 동일 이메일로 이메일 가입 선행 시 409 반환("이메일 로그인 이용"). 자동 계정 연동 미지원(보안·UX 트레이드오프).
- **OAuth 자동가입 동의**: TERMS/PRIVACY/DISCLAIMER=true, MARKETING=false 기본 처리. 자본시장법 §11 면책조항 포함.
- **OAuthProviderClient 인터페이스에 state 파라미터**: Naver 토큰 교환에 state 필수 → 인터페이스에 포함. Kakao/Google은 무시.
- **SecurityConfig 무변경**: `/api/v1/auth/**` 이미 permitAll 커버.

### 미완료 (Wave 5)
- `AuthIntegrationTest` (Testcontainers PostgreSQL) — signup/login/refresh/logout 흐름 검증
- `PortfolioIntegrationTest` — IDOR 403, Free 422, AES decrypt 검증
- Spec `user-auth-jwt-oauth2` → **Done** 전환 (`/dc-spec-move user-auth-jwt-oauth2 Done`)

---

## 2026-06-07 | M2 user-auth Wave 3 — 사용자·포트폴리오·알림설정·종목검색

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 3: Cards 12-14)

### 완료
- **UserService** + **UserController** — `GET/PATCH/DELETE /api/v1/users/me` (soft delete + 전 기기 로그아웃)
- **PortfolioService** (CRUD + Free 3종목 제한 + AES-256-GCM 암복호 + IDOR 방지) + **PortfolioController** — `GET/POST/PUT/DELETE /api/v1/portfolios`
- **NotificationSettingsService** + **NotificationSettingsController** — `GET/PUT /api/v1/users/me/notifications`
- **StockSearchController** — `GET /api/v1/stocks/search?q=` PUBLIC (max 20건, @Validated @RequestParam)
- **DTO 6종**: UserMeResponse, UpdateMeRequest, PortfolioRequest, PortfolioResponse, NotificationSettingsRequest, NotificationSettingsResponse
- **StockRepository.search()** — JPQL LIKE 쿼리(corpName 포함·stockCode 포함, Pageable 20건)
- **GlobalExceptionHandler** — `ConstraintViolationException` 핸들러 + `AesGcmEncryptor.CryptoException` 핸들러 추가

### 결정
- **IDOR 패턴**: `findByIdAndUserId` 스코프 쿼리 → 미존재/권한없음 모두 403. 404와 구분 없음(정보 최소화 허용).
- **tier 추출**: JWT claims `ROLE_{TIER}` → `UserEntity.Tier.valueOf()` 인메모리 변환. DB 조회 없음.
- **PortfolioRequest 공유**: create/update 동일 DTO 사용. update 시 stockCode는 `PortfolioEntity.update()`에 전달되지 않아 변경 불가. stockCode 변경 필요 시 DELETE → POST 흐름 안내.
- **CryptoException 처리**: 500 내부 오류로 반환(AES_KEY 변경·DB 손상 시 발생). 스택 트레이스는 서버 로그에만 기록.

### 미완료 (Wave 4)
- `KakaoOAuthClient` + `GoogleOAuthClient` + `NaverOAuthClient` (RestClient + @Retryable)
- `AuthService` OAuth 콜백 (oauth_id 매칭 → 로그인/자동가입)
- `AuthController` OAuth 엔드포인트 (`GET /auth/oauth/{provider}/url`, `POST /auth/oauth/{provider}/callback`)

---

## 2026-06-07 | M2 user-auth Wave 2 — 이메일 Auth 서비스·컨트롤러·DTO

**Spec**: `docs/specs/Approved/user-auth-jwt-oauth2.md` (Wave 2)

### 완료
- **DTO 4종**: `SignupRequest`(@AssertTrue terms/privacy/disclaimer 필수, @Email, @Size 8-72), `LoginRequest`, `AuthResponse`(@JsonProperty snake_case, expiresIn), `RefreshRequest`
- **GlobalExceptionHandler** (shared/exception) — `@Valid` 실패 → RFC 7807 ProblemDetail 필드별 메시지
- **ConsentService** — `recordSignupConsents` saveAll batch 4건 INSERT, `findLatest`
- **AuthService** — signup(BCrypt+consent 동일 트랜잭션), login(계정열거방지 동일 401), refresh(rotation: deleteOld+saveNew), logout(멱등), forceLogout(deleteByUserId)
- **AuthController** — `POST /api/v1/auth/{signup/login/refresh/logout}`, signup→201, logout→204
- **application.yml** — `spring.mvc.problemdetails.enabled: true`
- 리뷰 수정: ConsentService saveAll batch (Medium 1건 즉시 반영)

### 결정
- **계정 열거 방지**: 이메일 미존재·비밀번호 불일치 모두 `"인증 실패"` 동일 응답
- **refresh token 응답**: body에 raw 값 반환(프론트 보안 저장소 책임). HttpOnly Cookie 전환은 프론트 협의 후
- **logout 멱등**: 미존재 hash 삭제 시 조용히 성공(클라이언트 재시도 안전)
- **만료 토큰 클린업**: Wave 5 통합 테스트 시 `@Scheduled + deleteExpiredTokens()` 추가 예정

### 미완료 (Wave 3)
- `UserService` + `UserController` (me/update/soft-delete) + `UserMeResponse`/`UpdateMeRequest` DTO
- `PortfolioService` (CRUD + Free 3종목 제한 + AES 암복호) + `PortfolioController` + DTO
- `StockSearchController` (`GET /api/v1/stocks/search?q=` PUBLIC)
- `NotificationSettingsService` + `NotificationSettingsController`

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

### 미완료 → Wave 2에서 완료됨 ✅

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
