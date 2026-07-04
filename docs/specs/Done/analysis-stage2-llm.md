---
type: spec
status: Done
created: 2026-06-03
updated: 2026-06-08
tech_reviewed: 2026-06-03
approved: 2026-06-04
related:
  - "[[CLAUDE]]"
  - "[[DART공시통역_통합기획서]]"
  - "[[feature_structure]]"
  - "[[db_schema]]"
  - "[[api_spec]]"
  - "[[disclosure-collection-pipeline]]"
---

# analysis Stage 2 LLM — 호재/중립/악재 1차 분류 Spec

> 상태: Approved (2026-06-04) → **Done** (2026-06-08, Wave 1~5 완료 + qwen3:4b 확정 + 91k 백필 통계)
> 마일스톤: **M1** (Phase 1 데이터 토대 → 분석 도메인 진입)
> 선행 의존: [[disclosure-collection-pipeline]] (완료, 91,965건 적재 + 룰 분류 OTHER 8%)

---

## 1. 배경 / 목적

### 1.1 문제 정의

공시 91,965건이 Stage 1 룰 분류까지 적재됐고 폴링도 가동 중이지만, **호재/중립/악재 의미 해석이 0건**이다. 사용자가 보유 종목 공시를 받아도 "사업보고서 제출"같은 원본 제목만 노출되어 통합기획서 §1 "DART 공시를 자연어로 해석"이라는 가치 명제를 충족하지 못한다.

Stage 2 LLM 1차 분류는 다음을 산출한다(통합기획서 §6.1 Stage 2):

- **분류**: `POSITIVE` / `NEUTRAL` / `NEGATIVE`
- **신뢰도**: `0~1` (낮으면 `is_withheld=true` → "판단 보류")
- **요약**: 자연어 3줄 (Free 티어 노출용 한 줄 요약은 응답 후처리)

### 1.2 BM 티어 매핑 (통합기획서 §3.3 / §8.1)

- **Free** : Stage 1~2 노출 → 본 Spec 산출물이 Free 노출 범위의 **마지막** 단계
- **Pro+** : Stage 3~4 (후속 Spec, M1 후행)
- **Premium** : Stage 5 (후속 Spec)

### 1.3 페르소나 연결

- **A 직장인 개인 투자자**, **C 시니어** : "전환사채 발행이 호재인가 악재인가" 판단 자체가 진입 장벽. Stage 2 sentiment + 3줄 요약이 1차 해결책.
- **E 입문 투자자** : 신뢰도 낮을 때 "판단 보류" 표시 → 잘못된 정보로 인한 손실 회피 (통합기획서 §11.1 리스크 2).

---

## 2. 요구사항

### 2.1 기능 요구사항

- [ ] **F1**: 신규 공시 적재 시 Stage 2 분석을 **자동 트리거** (이벤트 기반, AFTER_COMMIT + @Async)
- [ ] **F2**: 기존 91,965건 공시에 대한 **배치 백필 잡** 제공 (BackfillJob 패턴 재사용, jobId 진행률 조회)
- [ ] **F3**: LLM 응답은 **Java record 스키마로 파싱 후 저장** — 파싱 실패 시 재시도, 3회 실패 시 `stage_reached=1` 유지하고 운영 로그
- [ ] **F4**: `analysis_results` UPSERT — 공시당 1건(`uq_analysis_disclosure`)
- [ ] **F5**: 신뢰도 임계치(기본 0.5) 미만 시 `is_withheld=true`
- [ ] **F6**: 분석 결과 조회 REST API `GET /api/v1/disclosures/{id}/analysis` ([[api_spec]] §2.4) — 티어별 필드 차등, `disclaimer` + `report_inaccuracy_path` 항상 포함
- [ ] **F7**: `AnalysisCompletedEvent` 발행 (notification 도메인 후속 Spec이 구독)
- [ ] **F8**: LLM provider 추상화 (`infrastructure/llm/LlmClient`) — MVP **Ollama Local**, 실서비스 Cloud(OpenAI/Anthropic) 어댑터 교체만으로 전환

### 2.2 비기능 요구사항

- [ ] **NF1**: 신규 공시 1건 Stage 2 처리 **30초 이내** (통합기획서 §8.3 SLO)
- [ ] **NF2**: 백필 잡 동시성 제한 (worker pool 크기 조절) — Ollama 단일 인스턴스 RPS 한계 회피
- [ ] **NF3**: 토큰 사용량 영속화 (`input_tokens`, `output_tokens`) — 운영 비용 추적
- [ ] **NF4**: 통합 테스트는 LLM **Mock 어댑터** + **Testcontainers PostgreSQL** ([[CLAUDE]] §6-6) — 실제 Ollama는 통합 테스트에서 호출 금지(불안정)
- [ ] **NF5**: 백필 잡은 `/admin/**` HTTP Basic 가드 하위에 배치

### 2.3 법/규제 요구사항 (통합기획서 §11.1)

- [ ] **L1**: LLM 프롬프트에 **"투자 권유 표현 금지"** 가드 명시 ("매수/매도 추천", "꼭 사세요" 등 금지)
- [ ] **L2**: 응답 후처리 — 금지 표현 키워드 매칭 시 `is_withheld=true` 강제 + 요약 sanitize
- [ ] **L3**: 모든 분석 응답에 면책 문구 + 신고 경로 동반 (응답 직렬화 계층 강제)

---

## 3. 영향 범위 (조사 결과)

### 3.1 영향 레이어

- **backend** : `analysis` 도메인(신규), `infrastructure/llm`(신규), `disclosure`(이벤트 발행 1줄 추가), `shared`(이벤트 타입)
- **frontend** : 없음 (별도 Spec — M4)
- **scripts** : 없음

### 3.2 신규 파일

```
backend/src/main/java/com/dartcommons/
├── analysis/
│   ├── controllers/
│   │   ├── AnalysisController.java               # GET /api/v1/disclosures/{id}/analysis
│   │   └── AnalysisBackfillController.java       # POST /admin/analysis/backfill, GET /admin/analysis/backfill/{jobId}
│   ├── services/
│   │   ├── AnalysisOrchestrator.java             # @TransactionalEventListener(AFTER_COMMIT) + @Async
│   │   ├── Stage2Analyzer.java                   # LLM 1차 분류 호출 + 파싱 + UPSERT
│   │   ├── AnalysisBackfillService.java          # 배치 백필(91k건, chunk 처리)
│   │   ├── AnalysisJobService.java               # jobId 진행률
│   │   └── PromptGuard.java                      # 금지 표현 후처리
│   ├── repositories/
│   │   ├── AnalysisResultRepository.java
│   │   └── AnalysisJobRepository.java            # backfill_jobs 재사용 or 신규 테이블 (§5.2 결정)
│   ├── entities/
│   │   └── AnalysisResult.java                   # V5 매핑
│   ├── dto/
│   │   ├── Stage2Output.java                     # record { sentiment, confidence, summary }
│   │   ├── AnalysisResponse.java                 # REST 응답(티어별 필드)
│   │   └── AnalysisProgressResponse.java
│   └── package-info.java                          # (기존)
├── infrastructure/llm/
│   ├── LlmClient.java                            # 인터페이스(provider 추상화)
│   ├── OllamaLlmClient.java                      # langchain4j-ollama 어댑터
│   ├── MockLlmClient.java                        # @Profile("test") — record 고정 응답
│   └── LlmProperties.java                        # @ConfigurationProperties("dartcommons.llm")
└── shared/
    └── events/
        ├── DisclosureCollectedEvent.java         # disclosureId
        └── AnalysisCompletedEvent.java           # analysisId, disclosureId, sentiment, confidence, isWithheld
```

### 3.3 수정 파일

- `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureCollectionService.java` — INSERT 직후 `ApplicationEventPublisher.publishEvent(new DisclosureCollectedEvent(...))` 추가
- `backend/build.gradle` — `dev.langchain4j:langchain4j-ollama:1.x` + `dev.langchain4j:langchain4j-spring-boot-starter:1.x` 의존성 추가 (정확 버전은 구현 시 BOM 확인)
- `backend/src/main/resources/application.yml` — `dartcommons.llm.*` 블록 추가 (provider, baseUrl, model, timeout, confidenceThreshold)
- `.env.example` — `OLLAMA_BASE_URL`, `LLM_MODEL` 등 (운영 환경 가이드)

### 3.4 DB 변경

- **`analysis_results`(V5) 신규 마이그레이션 불필요** — 스키마 이미 존재(검증 완료).
- **결정 필요** (§5.2): `backfill_jobs`(V12) 재사용 vs `analysis_jobs` 신규 테이블 → tech-review에서 결정.

### 3.5 외부 계약

- **신규**: Ollama HTTP API (LangChain4j 어댑터 경유)
- **변경**: 없음 (DART/KRX/카카오는 본 Spec 범위 밖)

### 3.6 Chroma / 벡터 DB

- **사용 안 함** (Stage 3 범위). 본 Spec은 텍스트 입출력만.

---

## 4. 관련 패턴 / 과거 사례

> Step 0 learnings: `docs/solutions/` 디렉토리 부재 → 과거 사례 메모리 없음. 코드베이스 내부 패턴만 인용.

### 4.1 재사용 패턴

| 항목 | 참조 위치 | 재사용 방식 |
|------|----------|-----------|
| **배치 백필 + jobId 진행률** | `disclosure/services/DisclosureBackfillService.java` + `BackfillJobService.java` + V12 `backfill_jobs` | 동일 구조로 `AnalysisBackfillService` 구성. 청크 크기는 LLM RPS 한계로 50~100 권장(백필 500과 다름) |
| **@Async + @Scheduled 설정** | `shared/.../SchedulingConfig.java` (`@EnableAsync` + `@EnableRetry` + `@EnableScheduling` 적용) | 그대로 활용. `analysisExecutor` Bean 분리(폴링/수집 풀과 격리) |
| **외부 클라이언트 + @Retryable** | `infrastructure/dart/DartClient.java` | `LlmClient` 어댑터도 동일 — 타임아웃 + 지수 백오프 (단 LLM 응답 시간 가변, retry는 2~3회로 보수적) |
| **@ConfigurationProperties + .env 주입** | `infrastructure/dart/DartApiProperties.java` + bootRun .env 로더 | `LlmProperties` 동일 패턴 |
| **`/admin/**` HTTP Basic 가드** | `shared/SecurityConfig.java` + `AdminAuthProperties` | 백필 컨트롤러는 `/admin/analysis/...` 경로 → 자동 적용 |
| **Testcontainers 통합 테스트** | `backend/src/test/java/.../disclosure/...` | LLM은 `MockLlmClient` 주입(@Profile("test")), DB는 실제 Postgres 컨테이너 |

### 4.2 도메인 이벤트 패턴 (신규 도입)

[[feature_structure]] §1.2: `disclosure → (DisclosureCollectedEvent) → analysis → (AnalysisCompletedEvent) → notification`. 본 Spec이 **이 이벤트 채널의 1차 도입**이다 — `shared/events/` 패키지가 신규 생성된다.

---

## 5. 리스크 / 법적 검토 / 결정 필요

### 5.1 리스크

| 리스크 | 영향 | 완화책 |
|--------|------|--------|
| **LLM 환각** ([[CLAUDE]] §6-6) | 잘못된 호재/악재 단정 → 사용자 손실 → 책임 분쟁 (통합기획서 §11.1 리스크 2) | Java record 스키마 강제 파싱, 신뢰도 임계치 기반 `is_withheld`, 금지 표현 후처리, 면책 문구 |
| **자본시장법 경계** (통합기획서 §11.1 리스크 1) | "추천"/"매수 권유" 표현 시 자문업 등록 의무 위반 가능 | 프롬프트 가드(L1) + 응답 후처리 키워드 필터(L2) + 모든 응답에 disclaimer(L3) |
| **Ollama RPS 한계** | 백필 91k건 시 단일 인스턴스 큐 폭주 | 청크 크기 50~100, worker 동시성 cap, 우선순위는 신규 폴링 공시 > 백필 |
| **토큰 비용 폭주** (실서비스 전환 시) | 91k × Cloud 입력 1.5k 토큰 = 무시 못함 (통합기획서 §6.2: Free 합계 $0.0001~0.005/건) | `input_tokens`/`output_tokens` 영속화 + 일별 cap 환경변수(NF3, 후속) |
| **`contentText` 미존재** | Disclosure 엔티티 주석상 본문 추출은 "후속 Spec" — Stage 2 입력은 `report_nm` + `disclosure_type`만 사용 가능 | 본 Spec은 메타 기반 분류로 한정. **본문 추출은 별도 Spec에서 다루고, Stage 2 결과는 본문 추가 시 재분석 대상** (재분석 트리거는 후속) |
| **Stage 1 룰 OTHER 8%** | OTHER는 Stage 2 분류 정확도 저하 가능 | 프롬프트에 `disclosureType=OTHER` 분기 — "report_nm 키워드만 신뢰"로 가이드. 신뢰도 자연 하락 → `is_withheld` 자동 작동 |

### 5.2 결정 필요 (tech-review에서 확정)

1. **백필 잡 테이블**: 기존 `backfill_jobs`(V12)에 `job_type` 컬럼 추가로 disclosure/analysis 공용 vs `analysis_jobs` 신규 V13 마이그레이션
2. **신뢰도 임계치 기본값**: 0.5 vs 0.6 vs 0.7 (Free 노출 보수성 트레이드오프)
3. **백필 우선순위 큐 분리**: 신규 폴링 공시 풀 vs 백필 풀 ExecutorService 분리 여부
4. **MVP 모델**: Ollama `llama3.1:8b` vs `qwen2.5:7b` vs `gemma2:9b` (통합기획서 §6.3 미정)
5. **Free 응답 노출 범위**: 통합기획서 §8.1 "한 줄 요약" vs §6.1 Stage 2 "3줄 요약" 불일치 — Free 응답은 3줄 그대로 vs 첫 줄만 vs 텍스트 가공
6. **백필 잡 트리거 UI**: `/admin` 엔드포인트 cURL만 vs 간단한 admin 페이지(M4 합류 시점에)

### 5.3 법적 검토 체크리스트

- [ ] 프롬프트에 자본시장법 가드 문구 포함 ("투자 자문 아님, 정보 제공 목적")
- [ ] 응답 후처리에서 금지 키워드 매칭 (`추천`·`매수`·`매도`·`사세요`·`수익 보장` 등) — 매칭 시 `is_withheld=true`
- [ ] `disclaimer` 필드 응답 직렬화 강제 (DTO에서 누락 불가)
- [ ] `report_inaccuracy_path` 항상 동반 ([[api_spec]] §2.4)

---

## 6. 권장 구현 방향

### 6.1 아키텍처 (이벤트 기반 비동기)

```
[Stage 1 완료]
  DisclosureCollectionService.collect()
    └─ disclosures INSERT (트랜잭션 커밋)
       └─ publish DisclosureCollectedEvent(disclosureId)
                  │ @TransactionalEventListener(AFTER_COMMIT)
                  │ @Async("analysisExecutor")
                  ▼
            AnalysisOrchestrator.onDisclosureCollected(event)
              └─ Stage2Analyzer.analyze(disclosure)
                   1. PromptBuilder: report_nm + disclosure_type + corp_name + rcept_dt
                   2. LlmClient.complete(prompt, Stage2Output.class) [record 스키마 강제]
                   3. PromptGuard.sanitize(output) [금지 키워드 후처리]
                   4. confidence < threshold → is_withheld=true
                   5. analysis_results UPSERT (stage_reached=2)
                   6. publish AnalysisCompletedEvent(analysisId, ...)
```

### 6.2 LLM 입출력 스키마 (환각 방지 핵심)

**입력**: report_nm + disclosure_type + corp_name + rcept_dt (본문 미사용)

**출력 record** (LangChain4j AiServices `@StructuredPrompt` + JSON 모드):

```java
public record Stage2Output(
    Sentiment sentiment,     // POSITIVE | NEUTRAL | NEGATIVE
    BigDecimal confidence,   // 0.000 ~ 1.000
    String summary           // 3줄, 마침표 구분, 최대 N자
) {}
```

파싱 실패 또는 enum 매칭 실패 → 1회 재호출. 2회째 실패 → `stage_reached=1` 유지 + WARN 로그(silent fail). 사용자 응답은 "분석 준비 중" 처리.

### 6.3 백필 전략 (91k건)

- 청크 단위(50~100) × @Async worker N개 (LLM RPS 한계로 결정)
- `analysis_results.disclosure_id` 미존재 행만 대상 (LEFT JOIN ... WHERE ar.id IS NULL)
- jobId 진행률 조회 ([[disclosure-collection-pipeline]] BackfillJob 패턴 그대로)
- 운영자 트리거: `POST /admin/analysis/backfill { fromDate, toDate, limit }` (HTTP Basic)
- 폴링 신규 공시 우선 — 백필 ExecutorService는 별도 풀(낮은 우선순위)

### 6.4 응답 직렬화 (티어 차등)

```
AnalysisResponse {
  analysisId, disclosureId,
  sentiment, confidence, isWithheld, summary,
  stageReached,
  // Pro 이상에서만 직렬화(필드 자체 누락)
  expectedReaction?, rationale?, similarDisclosures?,
  // Premium에서만
  financialContext?,
  // 항상
  disclaimer, reportInaccuracyPath, createdAt
}
```

티어 미달 필드는 **응답에서 제외(JSON 키 자체 없음)**, 노출 후 마스킹 금지 ([[api_spec]] §2.4 불변 규칙).

### 6.5 단계적 구현 wave 제안 (tech-review에서 확정)

- **wave 1**: 도메인 골격 + 이벤트 채널 + Mock LLM 통합 테스트 (모든 코드 + 테스트, 단 LLM은 mock)
- **wave 2**: Ollama 어댑터 + 실 모델 1건 smoke test + 프롬프트 튜닝 + PromptGuard
- **wave 3**: 백필 잡 + AdminController + Testcontainers backfill 테스트
- **wave 4**: REST API (`GET /api/v1/disclosures/{id}/analysis`) + 응답 직렬화 + 통합 테스트
- **wave 5**: 91k 백필 dry-run + 신뢰도/토큰 통계 분석 + 운영 가이드

---

## 7. 성공 기준

- [ ] **G1**: 신규 폴링 공시가 1분 이내 적재 + 30초 이내 Stage 2 분석 완료 (NF1)
- [ ] **G2**: 백필 91k건 완주 + `analysis_results` 91k 행 (실패율 < 1%)
- [ ] **G3**: 통합 테스트 — 신규 분석 골든 패스 + 파싱 실패 폴백 + 신뢰도 임계 + 금지 키워드 후처리 + 백필 진행률 (5+ 시나리오)
- [ ] **G4**: 신뢰도 분포 통계 — 평균 0.5+ 목표, `is_withheld` 비율 30% 이하 (튜닝 가이드)
- [ ] **G5**: 자본시장법 검수 — 100건 샘플에서 금지 표현 0건

---

## 8. 범위 외 (out of scope)

- **본문 텍스트 추출** (`disclosures.content_text` 채우기) — 별도 Spec
- **Stage 3~5** (RAG, 최종 판단, 재무/업황) — M1 후속 Spec
- **AnalysisCompletedEvent 구독자(notification)** — M3 Spec
- **프론트엔드 분석 결과 UI** — M4 Spec
- **재분석/재처리 UI** — 본문 추출 Spec과 함께
- **Cloud LLM 어댑터 구현 본체** — 인터페이스만 정의, OpenAI/Anthropic 어댑터는 실서비스 단계
- **분산 락(ShedLock)** — 폴링·백필 동시 인스턴스 운영 시 ([[feature_structure]] §4 주석) — Phase 3

---

## 9. 참고

- [[CLAUDE]] §3-2 (도메인 모듈 표준), §6-6 (환각 방지/Mock DB 금지), §7 (자본시장법 표현 금지)
- [[DART공시통역_통합기획서]] §6.1 Stage 2 명세, §8.1 BM 티어, §11.1 리스크
- [[feature_structure]] §1.2 이벤트, §2 시퀀스, §3 Stage 파이프라인
- [[db_schema]] §3.5 analysis_results, §3 enum/CHECK 매핑 힌트
- [[api_spec]] §2.4 분석 REST 응답 스펙
- [[disclosure-collection-pipeline]] (선행, Approved) — Stage 1 산출물 + 백필 잡 패턴 참조원

---

## Tech Review (dc-tech-review · 2026-06-03)

### 0. Spec 정정 사항 (코드베이스 실측 결과)

기획 단계에서 가정한 내용 중 **이미 구현된 부분**이 확인되어 본 Spec의 일부 신규 파일·작업이 불필요해졌다.

| Spec 가정 (§3.2 / §4.2) | 실측 | 영향 |
|------|------|------|
| `shared/events/DisclosureCollectedEvent.java` 신규 생성 | **이미 존재** : `shared/event/DisclosureCollectedEvent.java`(record, `disclosureId` 단일 필드) | 패키지명은 `events`가 아닌 `event` (단수). 신규 작성 불필요 |
| `DisclosureCollectionService`에 이벤트 발행 1줄 추가 | **이미 발행 중** : `DisclosureCollectionService.java:105` + `DisclosureBackfillService.java:179` | 수정 작업 0. analysis는 리스너만 구현하면 됨 |
| `SchedulingConfig`에 `@EnableAsync` 추가 | **이미 활성화** | 그대로 사용. 단 `TaskExecutor` Bean 미설정 → SimpleAsyncTaskExecutor(매 호출 새 스레드). **백필 91k 시 스레드 폭주 위험 → 신규 풀 Bean 필수** |
| `backfill_jobs`(V12) 재사용 vs 신규 테이블 (§5.2 결정 1) | V12는 `from_date`/`to_date` NOT NULL — 날짜 범위 의미론 고정. analysis 백필은 "미분석 공시 ID 범위" 의미 → **시맨틱 불일치** | **신규 V13 `analysis_jobs` 테이블** 권장 (결정 1 확정) |
| `shared/events/AnalysisCompletedEvent.java` 신규 | 없음 → 신규 작성 | 정상. 패키지는 `shared/event/` (실측 디렉토리 명) |

→ 본 Spec §3.2/§4.2의 패키지명 `shared/events/`는 **`shared/event/`로 정정** (구현 시 적용).

---

### 1. 아키텍처 분해

- **영향 레이어**: backend(`analysis` 신규 도메인, `infrastructure/llm` 신규, `shared/event` 1건 추가, `shared/config` Async 풀 Bean 추가)
- **신규 클래스 / 컴포넌트**:
  - `analysis/` : Orchestrator(이벤트 리스너), Stage2Analyzer, AnalysisBackfillService, AnalysisJobService, PromptGuard, Controller × 2, Entity × 2(AnalysisResult + AnalysisJob), Repository × 2, DTO × 4
  - `infrastructure/llm/` : LlmClient(IF), OllamaLlmClient, MockLlmClient(@Profile("test")), LlmProperties
  - `shared/event/` : AnalysisCompletedEvent (record)
  - `shared/config/` : ExecutorConfig (analysisExecutor + analysisBackfillExecutor Bean)
  - DB: V13__create_analysis_jobs.sql
- **수정 클래스 / 컴포넌트**:
  - `build.gradle` : LangChain4j BOM + ollama 모듈 추가
  - `application.yml` : `dartcommons.llm.*` 블록
  - `.env.example` : Ollama 환경변수 가이드
- **불변 (수정 없음)** : `disclosure/`, `stocks/`, `SecurityConfig`(자동 적용), `SchedulingConfig`

---

### 2. 결정 사항 확정 (Spec §5.2 6건)

| # | 결정 항목 | 확정 | 근거 |
|---|----------|------|------|
| 1 | 백필 잡 테이블 | **신규 V13 `analysis_jobs`** | V12는 `from_date NOT NULL` — analysis 백필은 disclosure ID 범위 + 필터 의미라 시맨틱 충돌. job_type 추가 + 컬럼 nullable 변경 시 기존 행 의미 깨짐 |
| 2 | 신뢰도 임계치 기본값 | **0.6** (SystemConfig 키 `analysis.stage2.confidence_threshold`로 런타임 조정 가능) | Free 노출 보수성과 `is_withheld` 비율(목표 ≤30%, Spec §7 G4)의 균형. 0.5는 너무 관대(절반 가까이 통과), 0.7은 과보수(보류율 폭증). 운영 보정 가능 구조로 안전망 |
| 3 | 워커 풀 분리 | **분리: `analysisExecutor`(폴링 트리거용, core 2 / max 4) + `analysisBackfillExecutor`(백필용, core 1 / max 2)** | Ollama 단일 인스턴스 RPS 한계 — 백필이 폴링 트리거 큐를 막으면 SLO(30초) 위반. 풀 분리로 우선순위 격리 |
| 4 | MVP 모델 | **`qwen3:4b` 확정** (2026-06-05 wave 2 smoke test) — 초기 후보 `qwen2.5:7b-instruct`는 기획서 §6.3 "Qwen 3.5" 명세 + ollama registry 실재 버전(qwen3:4b 4.0B Q4_K_M) 정합으로 교체 | 5건 비교: qwen3는 모두 NEUTRAL 보수적(잘못된 단정 0건). gemma3:4b는 클로봇 유상증자(통상 악재) → POSITIVE 오분류 + summary "주가에 긍정적 영향" 자본시장법 위반 위험. qwen3 평균 3.1초로 더 빠름. 모델 정확도 한계는 Stage 3 RAG + Stage 4 2차 분석으로 보강(본 Spec §8 범위 외). 상세: `docs/dev-log/analysis-stage2-smoke.md` |
| 5 | Free 응답 범위 | **summary 3줄 그대로 노출** | 통합기획서 §6.1(Stage 2 "3줄 요약") = SSOT. §8.1(Free "한 줄 요약")은 BM 카피의 단순화 — 마케팅 영역과 분리. 단, summary 글자수 cap 240자(3 × 80)로 LLM 횡설수설 방지 |
| 6 | 백필 트리거 UI | **MVP는 cURL만** (`POST /admin/analysis/backfill` HTTP Basic) | M4 frontend Spec 합류 시점에 admin 페이지 검토. 본 Spec 범위는 백엔드 API + 운영 가이드 doc 한정 |

---

### 3. 작업 카드

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| **T1** | V13__create_analysis_jobs.sql + JPA 엔티티 (AnalysisJob) + Repository | backend/db | 하 | — |
| **T2** | AnalysisResult 엔티티(V5 매핑) + Repository + `findByDisclosureId` + UPSERT 헬퍼 | backend/analysis | 하 | — |
| **T3** | `LlmClient` IF + `LlmProperties`(@ConfigurationProperties) + `MockLlmClient`(@Profile("test")) | infrastructure/llm | 하 | — |
| **T4** | `OllamaLlmClient` (langchain4j-ollama 어댑터, 타임아웃 + @Retryable 2회) | infrastructure/llm | 중 | T3, build.gradle |
| **T5** | build.gradle: LangChain4j BOM + ollama 모듈 / application.yml `dartcommons.llm.*` / .env.example | infra | 하 | — |
| **T6** | `ExecutorConfig` : `analysisExecutor` + `analysisBackfillExecutor` Bean (결정 3) | backend/shared | 하 | — |
| **T7** | `Stage2Output` record + `Stage2Analyzer` (프롬프트 빌더 + LlmClient 호출 + 파싱 재시도 1회) | backend/analysis | **상** | T3 |
| **T8** | `PromptGuard` : 금지 키워드 후처리(L2) + summary 글자수 cap(결정 5) | backend/analysis | 중 | T7 |
| **T9** | `AnalysisOrchestrator` : `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("analysisExecutor")` → Stage2Analyzer → AnalysisResult UPSERT → `AnalysisCompletedEvent` 발행 | backend/analysis | 중 | T2, T6, T7, T8 |
| **T10** | `AnalysisCompletedEvent` record (analysisId, disclosureId, sentiment, confidence, isWithheld) | shared/event | 하 | — |
| **T11** | `AnalysisBackfillService` (`analysis_results.disclosure_id IS NULL` 청크 100건, `analysisBackfillExecutor` 사용) + `AnalysisJobService` 진행률 | backend/analysis | 중 | T1, T6, T9 |
| **T12** | `AnalysisBackfillController` : `POST/GET /admin/analysis/backfill[/{jobId}]` (HTTP Basic 자동 적용) | backend/analysis | 하 | T11 |
| **T13** | `AnalysisResponse` DTO + 티어 차등 직렬화 + `disclaimer`/`reportInaccuracyPath` 강제 | backend/analysis | 중 | T2 |
| **T14** | `AnalysisController` : `GET /api/v1/disclosures/{id}/analysis` (404 분기: 미분석/판단 보류 표시) | backend/analysis | 하 | T13 |
| **T15** | 통합 테스트 — Orchestrator 골든 패스 / 파싱 실패 폴백 / 신뢰도 임계 / PromptGuard 금지 키워드 / 백필 진행률 / REST 티어 차등 (6+ 시나리오, MockLlmClient + Testcontainers) | backend/test | **상** | T9, T11, T14 |
| **T16** | Ollama smoke test 스크립트 (3~5건 실측, 모델 후보 비교 데이터 첨부) + 운영 가이드 doc(`docs/dev-log/...` 별도) | docs/scripts | 중 | T4 |
| **T17** | 91k 백필 dry-run + 신뢰도 분포 통계 + `is_withheld` 비율 측정 + 튜닝 메모 | ops/docs | 중 | T11, T16 |

> **카드 17개** — Spec §6.5 wave 5개에 매핑. 카드 1개 = 한 단위 commit 목표.

---

### 4. DB / 마이그레이션 영향

- **신규 파일**: `backend/src/main/resources/db/migration/V13__create_analysis_jobs.sql`
- **테이블 스키마(권장)**:

```sql
CREATE TABLE analysis_jobs (
    id              BIGSERIAL PRIMARY KEY,
    job_id          UUID         NOT NULL UNIQUE,
    status          VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
    stage           SMALLINT     NOT NULL CHECK (stage BETWEEN 2 AND 5),  -- 본 Spec=2, Stage 3+ 후속
    disclosure_id_from  BIGINT,                       -- 범위(nullable: NULL이면 전체 미분석분)
    disclosure_id_to    BIGINT,
    chunk_size      INTEGER      NOT NULL DEFAULT 100,
    chunks_total    INTEGER,
    chunks_done     INTEGER      NOT NULL DEFAULT 0,
    targeted        INTEGER      NOT NULL DEFAULT 0,  -- 대상 공시 수
    analyzed        INTEGER      NOT NULL DEFAULT 0,  -- 성공 분석 수
    failed          INTEGER      NOT NULL DEFAULT 0,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    error_message   VARCHAR(1000),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_analysis_jobs_status_created ON analysis_jobs (status, created_at DESC);
```

- **인덱스 검토**: `analysis_results` 백필 쿼리 `LEFT JOIN ... WHERE ar.id IS NULL` — `disclosures.id` PK 풀스캔 + analysis_results `uq_analysis_disclosure` UNIQUE 인덱스 활용 가능. 추가 인덱스 불필요(EXPLAIN으로 wave 3 확인).
- **V5(`analysis_results`) 컬럼 변경 없음** — 이미 본 Spec 모든 필드를 수용.

---

### 5. 외부 계약 영향

- **신규 외부 호출**: Ollama HTTP API (`/api/generate` 또는 `/api/chat`, LangChain4j 어댑터 경유)
  - 환경변수: `OLLAMA_BASE_URL`(기본 `http://localhost:11434`), `LLM_MODEL`(기본 `qwen3:4b`), `LLM_TIMEOUT_MS`(기본 60000)
  - 타임아웃: 60s (LLM 응답 가변성 — DART 10s보다 큼)
  - 재시도: 2회 (CLAUDE.md §4 정책 — 지수 백오프). 단 재시도는 **네트워크/타임아웃만**, JSON 파싱 실패는 별도 1회 재호출(Stage2Analyzer 책임)
- **변경 없음**: DART, KRX, 카카오 — 본 Spec 범위 밖
- **신규 REST 자체 계약**: `GET /api/v1/disclosures/{id}/analysis` ([[api_spec]] §2.4 명세 그대로 구현). 단 현재 `user` 도메인이 없으므로 **인증 가드는 임시 permitAll** + TODO 주석 → M2(user/JWT) 합류 시 `@PreAuthorize` 추가

---

### 6. 리스크 & 법적 검토

| # | 리스크 | 분류 | 완화책 (작업 카드) |
|---|-------|------|------------------|
| R1 | **LLM 환각으로 잘못된 호재/악재 단정** ([[CLAUDE]] §6-6, 통합기획서 §11.1) | 법적/기술 | T7(스키마 강제) + T8(키워드 후처리) + T13(disclaimer 강제). G5 검수 100건 |
| R2 | **자본시장법 — 투자 권유 표현 경계** (통합기획서 §11.1 리스크 1) | 법적 | T7 프롬프트 가드("정보 제공, 자문 아님" 명시) + T8 응답 키워드 필터(`추천`/`매수`/`매도`/`사세요`/`수익 보장`) → 매칭 시 `is_withheld=true` 강제 |
| R3 | **Ollama 단일 인스턴스 RPS 한계 → 백필이 폴링 큐 막음** | 운영 | T6 풀 분리 + T11 청크 100건 + 신규 폴링 우선순위 |
| R4 | **`disclosures.content_text=null` → 입력 부족 → 신뢰도 저하** | 기술 | T7 프롬프트가 `disclosure_type=OTHER` 명시 분기, 신뢰도 자연 하락 → R1 안전망과 연결. 본문 추출은 별도 Spec(범위 외) |
| R5 | **`user` 도메인 미구현 → REST 인증 공백** | 운영 | T14에 `permitAll` + TODO 주석, M2 Spec에서 `@PreAuthorize` 추가. 그동안 `/api/v1/disclosures/{id}/analysis`는 운영자/내부 접근만 가정 |
| R6 | **토큰 비용 (Cloud 전환 시)** | 비용 | T16 토큰 영속화(`input_tokens`, `output_tokens`) — Cloud 전환 시 별도 Spec에서 일별 cap 추가 |
| R7 | **AnalysisCompletedEvent 소비자 부재 (M3 전)** | 무해 | 소비자 없는 이벤트는 Spring이 무해 무시. 운영 영향 0 |

---

### 7. wave 분할

| wave | 카드 | 산출물 | 검증 게이트 |
|------|------|--------|------------|
| **wave 1** : 도메인 골격 + Mock | T1 T2 T3 T5 T6 T10 T13 (Mock 기반 통합 테스트 일부) | DB 마이그레이션 통과, Mock 통합 테스트 골든 패스 1건 | `/dc-test-verify` |
| **wave 2** : LLM 어댑터 + 프롬프트 | T4 T7 T8 T16 | Ollama smoke test 3~5건 실측 데이터, 모델 후보 비교 | `/dc-review-code` (R1/R2 집중) |
| **wave 3** : Orchestrator + 백필 | T9 T11 T12 | 신규 공시 자동 분석 + 백필 진행률 + Testcontainers 시나리오 | `/dc-test-verify` |
| **wave 4** : REST | T14 (T13/T15의 REST 시나리오) | `GET /api/v1/disclosures/{id}/analysis` 응답 + 티어 차등 | `/dc-review-code` |
| **wave 5** : 운영 검증 | T15(전체 통과) T17 | 91k dry-run + 신뢰도 통계 + 튜닝 메모 + Spec Done 제안 | `/dc-test-verify` + `/dc-doc-sync` |

→ 총 **5 wave**, 카드 17개. wave 2가 가장 위험(LLM 실측). wave 5는 운영 데이터 검증.

---

### 8. 검증 게이트 — Approved 전환 가능 판단

- [x] Spec 본문 + CLAUDE.md §3-2 §6 §7 + feature_structure §1.2 §3 + db_schema §3.5 + api_spec §2.4 모두 확인
- [x] 코드베이스 실측 정정 반영 (§0 — 이미 존재하는 이벤트/발행/Async 활성화)
- [x] DB 변경 = V13 단일 신규 마이그레이션, 컬럼 변경 0 (불변 원칙 준수)
- [x] 외부 계약 = Ollama HTTP 1건 추가, 키 환경변수 주입
- [x] 자본시장법/환각 리스크에 2중 방어 + 테스트 시나리오 명시
- [x] 결정 사항 6건 모두 확정
- [x] 작업 카드 17개 + wave 5개 + 의존성/난이도 명시

→ **Approved 전환 가능**. `/dc-spec-move analysis-stage2-llm Approved` 권장.
