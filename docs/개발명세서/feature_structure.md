---
type: doc
status: draft
created: 2026-05-30
updated: 2026-05-30
---

# 기능 구조 — 모듈 · 시퀀스 · 큐 운영 설계 (초안)

> **근거**: [[DART공시통역_통합기획서]] §4.4 공시 알림 발송 흐름 · §5.1 시스템 구성도 · §6 하이브리드 RAG 단계 · §7.3 데이터 동기화 · §9 알림 시스템.
> **연계**: 데이터 모델 [[db_schema]], 인터페이스 [[api_spec]], 화면 [[design_structure]].
> **규칙**: 도메인 간 직접 의존 금지 — `shared/` 또는 이벤트 경유, import 방향 `shared → 도메인`([[CLAUDE]] §3-2). 외부 호출은 `infrastructure/`로 격리 + 타임아웃·백오프([[CLAUDE]] §4). 공시·발송 멱등([[CLAUDE]] §4). LLM 응답 스키마 파싱 후 사용([[CLAUDE]] §6-6).
>
> 본 문서는 **초안**이다. Stage 구분·큐 방식 변동 시 함께 갱신한다(통합기획서 §6 변동 가능성 안내).

---

## 1. 모듈 구조 & 도메인 경계

```
com.dartcommons
├── disclosure/        # 공시 수집·룰 분류 (Stage 1)
├── analysis/          # LLM 분석 (Stage 2~5, LangChain4j)
├── notification/      # 알림 디스패처 (카카오/텔레그램/이메일)
├── user/              # 사용자·인증·보유종목·알림설정 (Spring Security + JWT)
├── infrastructure/    # 외부 API 클라이언트 (DART/KRX/공공, WebClient), Chroma, LLM provider
└── shared/            # 공통(이벤트, 예외, 암호화, 시각/장운영 유틸, BaseEntity)
```

각 모듈은 표준 폴더만 사용: `controllers/` · `services/` · `repositories/` · `entities/` · `dto/` · `guards/`(필요한 것만, 빈 폴더 금지, [[CLAUDE]] §3-2).

### 1.1 모듈 책임

| 모듈 | 책임 | 주요 협력 |
|------|------|------|
| `disclosure` | DART 폴링 수신, 메타 추출, 유형 룰 분류, 커버 종목 필터, `disclosures` 멱등 적재 | `infrastructure`(DART), `shared`(이벤트) |
| `analysis` | Stage 2~5 LLM 파이프라인, Chroma 임베딩/검색, 스키마 파싱, `analysis_results` 저장 | `infrastructure`(LLM/Chroma/KRX), `shared` |
| `notification` | 영향 사용자 조회, 큐 적재(즉시/다이제스트), 채널 발송, 재시도, `notifications` 기록 | `infrastructure`(카카오/텔레그램/메일), `shared` |
| `user` | 가입/인증/OAuth/JWT, 보유종목 CRUD, 알림설정, 동의 이력, 티어 | `shared`(암호화) |
| `infrastructure` | WebClient 클라이언트(DART/KRX/공공), LLM provider 어댑터, Chroma client | — (도메인 비참조) |
| `shared` | 도메인 이벤트, 공통 예외, AES-256-GCM 암복호, 장운영(거래시간/공휴일) 판단, BaseEntity | — |

### 1.2 도메인 간 통신 — 이벤트 경유

직접 호출 대신 **Spring `ApplicationEventPublisher` 도메인 이벤트**로 결합도를 낮춘다(역방향 import 금지 규칙 준수).

```
disclosure  ──(DisclosureCollectedEvent)──▶  analysis
analysis    ──(AnalysisCompletedEvent)────▶  notification
```

- 이벤트는 식별자 + 최소 페이로드(예: `disclosureId`, `analysisId`)만 싣고, 수신측이 자기 리포지토리에서 조회.
- 무거운 후처리(LLM/발송)는 `@Async` + `@TransactionalEventListener(AFTER_COMMIT)`로 분리 — 수집 트랜잭션과 분석/발송을 분리해 폴링 지연을 막는다.

---

## 2. 핵심 시퀀스 — 공시 수집 → 분석 → 발송

> 통합기획서 §4.4 자동 발송 흐름을 모듈/이벤트로 분해.

```
[@Scheduled 1분] DisclosurePollingJob
   │ 1. infrastructure.DartClient.list(bgn_de,end_de)         (타임아웃+백오프)
   │ 2. rcept_no 멱등 체크 (이미 적재 → skip)                  ← 중복 발송 방어 ①
   │ 3. Stage 1 룰 추출: 메타/유형 분류/본문 텍스트
   │ 4. 커버 종목 필터 (stocks 조인, 미커버 → skip)
   │ 5. disclosures INSERT
   └─▶ publish DisclosureCollectedEvent(disclosureId)
            │ (@Async, AFTER_COMMIT)
            ▼
       AnalysisOrchestrator
        │ Stage 2  LLM 1차: 분류+요약  → analysis_results UPSERT (sentiment·confidence·summary)
        │ Stage 3  임베딩→Chroma 유사검색(±회사/유형) + KRX 5일 반응 결합   [PRO+]
        │ Stage 4  LLM 2차: 최종판단+예상반응+근거 → 갱신(stage_reached=4) [PRO+]
        │ Stage 5  재무(DART)+업황(공공) 결합 → stage_details 보강         [PREMIUM]
        │ * 각 Stage LLM 응답은 record 스키마로 파싱 후 저장 (환각 방지)
        │ * confidence 낮으면 is_withheld=true (판단 보류)
        └─▶ publish AnalysisCompletedEvent(analysisId, disclosureId)
                 │ (@Async, AFTER_COMMIT)
                 ▼
            NotificationDispatcher
             │ 1. portfolios.stock_code 역조회 → 영향 사용자 집합
             │ 2. 사용자별 발송 자격 판정:
             │      - notify_enabled / type_filter(POSITIVE/NEGATIVE) 매칭
             │      - off_hours_allowed + shared 장운영 판단(거래시간/주말/공휴일)
             │      - tier로 분석 노출 범위 결정
             │ 3. frequency 분기:
             │      INSTANT       → 즉시 발송 큐
             │      DAILY_*/WEEKLY→ 다이제스트 버킷 적재 (배치가 수거)
             │ 4. notifications INSERT (status=PENDING)            ← 발송 멱등 ②
             │      UNIQUE(user, disclosure, channel) 충돌 → skip
             └─▶ ChannelSender (카카오 1순위 → 텔레그램/이메일 폴백)
                    성공 → status=SENT, sent_at
                    실패 → status=FAILED/RETRYING, retry_count++ (지수 백오프, 최대 3회)
```

**이중 멱등 방어**(통합기획서 §4.4[8], [[CLAUDE]] §4): ① `disclosures.rcept_no` UNIQUE, ② `notifications` `uq_notification_dedup(user, disclosure, channel)`.

---

## 3. Stage 파이프라인 ↔ 데이터/티어

| Stage | 처리 | LLM | 티어 | 읽기/쓰기 |
|-------|------|----|------|------|
| 1 룰 추출 | 메타·유형·본문(원본 불변) | 0회 | 전체 | `disclosures` INSERT |
| 2 LLM 1차 | 분류+3줄 요약+신뢰도 | 1회 | 전체 | `analysis_results` UPSERT |
| 3 임베딩+RAG | Chroma 유사검색 + KRX 5일 반응 | 임베딩 1회 | PRO+ | Chroma write/query |
| 4 LLM 2차 | 최종판단+예상반응+근거 | 1회 | PRO+ | `analysis_results` 갱신 |
| 5 재무/업황 | DART 재무 + 공공 업황 결합 | 1회 | PREMIUM | `stage_details` 보강 |

> 티어별 조기 종료: FREE는 Stage 2에서 파이프라인 종료. Stage 3+는 PRO 이상 사용자가 보유한 종목의 공시에 한해 수행(불필요한 LLM 비용 차단). 상세 단계 정의·토큰 추정은 통합기획서 §6.

---

## 4. 스케줄러 (배치 잡)

| 잡 | 주기 | 트리거 | 동작 |
|------|------|------|------|
| `DisclosurePollingJob` | **1분** | `@Scheduled(fixedDelay)` | DART `list.json` 폴링 → 신규 공시 수집 |
| `DigestDispatchJob` | 09:00 / 18:00 / 월 09:00 | `@Scheduled(cron)` | 다이제스트 버킷 수거 → 빈도별 묶음 발송 |
| `NotificationRetryJob` | 1분 | `@Scheduled` | `status IN (FAILED,RETRYING)` & 백오프 경과분 재시도 |
| `KrxPriceSyncJob` | 일 1회(장 마감 후) | `@Scheduled(cron)` | KRX 일별 시세 적재(5일 반응 산출 근거) |
| `FinancialSyncJob` | 분기 1회 | `@Scheduled(cron)` | DART 정기보고서 재무 적재(Stage 5) |
| `StockMasterSyncJob` | 분기 1회 | `@Scheduled(cron)` | KRX 종목 마스터 → `stocks` 갱신 |

> 다중 인스턴스 배포 대비: 스케줄 잡은 **ShedLock 등 분산 락**으로 중복 실행 방지(인프라 확정 시 적용, 통합기획서 §5.5). MVP 단일 인스턴스에서는 선택.

---

## 5. 알림 큐 설계

MVP는 **DB 기반 작업 큐**(`notifications` 테이블 상태머신)로 시작하고, 통합기획서 §16 Phase 3에서 외부 큐(SQS/Rabbit)로 승격한다.

### 5.1 상태 머신

```
PENDING ──발송시도──▶ SENT
   │                    ▲
   └──실패──▶ RETRYING ─┘ (백오프 경과 후 재시도)
                 │
              3회 초과
                 ▼
               FAILED (종결, 운영 알림)
```

### 5.2 즉시 vs 다이제스트

| 빈도 | 경로 |
|------|------|
| `INSTANT` | `AnalysisCompletedEvent` 수신 즉시 `notifications` 적재 + 발송 |
| `DAILY_1`/`DAILY_2`/`WEEKLY` | 다이제스트 버킷 적재 → `DigestDispatchJob`이 시각에 묶음 발송 |

> 다이제스트 대상 조회는 `idx_users_digest_target`(비-INSTANT·활성 부분 인덱스, [[db_schema]] §3.1 V7)로 최적화.

### 5.3 재시도 / 백오프 / 폴백

- 채널 발송 실패 → 지수 백오프(예: 1m·5m·30m), `retry_count` 증가, 최대 3회(§4.4[8]).
- **채널 폴백**: 카카오 발송 불가(승인/정책/장애) 시 사용자 선택 폴백 채널(텔레그램/이메일)로 전환(통합기획서 §9.1).
- 3회 초과 `FAILED` 종결 + 운영 로그/모니터링 신호.

---

## 6. 외부 연동 격리 (`infrastructure`)

| 클라이언트 | 대상 | 정책 |
|------|------|------|
| `DartClient` | DART OpenAPI | 타임아웃, 지수 백오프, `status` 코드 분기, `rcept_no` 멱등 위임 |
| `KrxClient` | KRX OpenAPI | 일/분기 배치, 타임아웃·재시도 |
| `PublicDataClient` | 공공 업황 API | 월 배치, 실패 시 Stage 5 graceful degrade |
| `LlmClient` | LangChain4j(Ollama/Cloud) | provider 추상화, 스키마 강제 파싱, 타임아웃 |
| `ChromaClient` | Chroma | 임베딩 upsert/query, 컬렉션 `disclosure_embeddings` |
| `KakaoAlimtalkClient`·`TelegramClient`·`MailClient` | 알림 채널 | 발송 결과 표준화, 폴백 신호 |

> 모든 키는 환경변수(`DART_API_KEY`·`KRX_API_KEY`·`KAKAO_*`·`OPENAI_API_KEY`/`ANTHROPIC_API_KEY` 등)로만 주입([[api_spec]] §3, [[CLAUDE]] §7).

---

## 7. 캐시 · 동시성

| 항목 | 방식 |
|------|------|
| 종목 마스터 조회 | Caffeine + Spring Cache(`stocks`, 분기 갱신이라 TTL 길게) |
| 커버리지 필터 | 커버 종목코드 집합 인메모리 캐시(폴링 핫패스) |
| 분석 결과 | 공시당 1건(`uq_analysis_disclosure`)이라 재계산 없음, 조회 캐시 선택 |
| 비동기 | 수집(동기, 빠름) / 분석·발송(@Async 풀 분리)로 폴링 지연 차단 |
| 트랜잭션 | 이벤트 리스너는 `AFTER_COMMIT` — 수집 커밋 후 분석/발송 시작(부분 실패 격리) |

---

## 8. 데이터 동기화 주기 (통합기획서 §7.3)

| 데이터 | 주기 | 잡 |
|------|------|------|
| 공시 | 1분 폴링 | `DisclosurePollingJob` |
| 주가 | 일 1회 | `KrxPriceSyncJob` |
| 재무 | 분기 1회 | `FinancialSyncJob` |
| 종목 기본정보 | 분기 1회 | `StockMasterSyncJob` |
| 업황 | 월 1회 | (공공 API 배치) |

---

## 9. 운영 체크 (이 구조 기준)

- [ ] 도메인 간 직접 의존 0 — 이벤트/`shared` 경유만([[CLAUDE]] §3-2)
- [ ] 수집·발송 이중 멱등(`rcept_no` / `uq_notification_dedup`)
- [ ] LLM 각 Stage 응답 record 파싱 후 저장, `confidence`/`is_withheld` 강제([[CLAUDE]] §6-6)
- [ ] 외부 클라이언트 타임아웃·백오프, `infrastructure` 격리, 키 환경변수
- [ ] 다이제스트 배치 분산 락(다중 인스턴스 시)
- [ ] 재시도 최대 3회 + 채널 폴백, `FAILED` 종결 모니터링
- [ ] 통합 테스트는 Testcontainers PostgreSQL(Mock DB 금지, [[CLAUDE]] §6-6)

---

## 관련 문서

- [[db_schema]] — 테이블/인덱스(상태머신·멱등 제약·부분 인덱스)
- [[api_spec]] — 외부 API 파라미터 · 자체 REST 인터페이스
- [[design_structure]] — 분석 결과 노출(티어/판단보류/면책)의 화면 처리
- [[DART공시통역_통합기획서]] §4.4·§5.1·§6·§7.3·§9 · [[CLAUDE]] §3-2·§4·§6-6
