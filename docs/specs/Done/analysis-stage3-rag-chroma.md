---
type: spec
status: Approved
created: 2026-06-24
updated: 2026-06-29
---

# Stage 3 RAG — Chroma 벡터 DB 도입 Spec

> 상태: **Approved** (2026-06-29, dc-tech-review 승인)
> 선행 Spec: [[analysis-stage2-llm]] (Done) — Stage 2 LLM 1차 분류 완료, Stage 3는 명시적 범위 외였음.

## 배경 / 목적

- **문제**: Stage 2 LLM 단독 분류의 정확도 한계가 실측으로 확인됨 — 500건 백필 결과 99% NEUTRAL / 1% NEGATIVE / **POSITIVE 0건** ([[analysis-stage2-wave5-stats]]). 모델 자체의 분류 자신감이 낮아 호재 신호를 거의 잡지 못함.
- **해결**: Stage 3 하이브리드 RAG — 공시 본문을 임베딩해 **과거 유사 공시**를 검색하고, 그 공시들의 실제 시장 반응(주가)을 근거로 결합하면 LLM 단독 판단의 환각·과소분류를 보강할 수 있음 (통합기획서 §6 하이브리드 RAG).
- **현재 상태**: PostgreSQL 12개 테이블·BE·FE는 가동 중이나 **Chroma는 컨테이너·설정·코드가 전무**(미착수). CLAUDE.md §2 기술스택과 [[db_schema]] §4가 SSOT로 `disclosure_embeddings` 컬렉션을 정의해 두었으나 구현 진입 전.
- **페르소나**: B(적극적 정보 수집), D(다종목 장기 투자) — "과거 유사 공시 + 주가 반응 차트" 가치 제공.
- **BM 티어**: **PRO+ 전용** (Free 1~2 / Pro 1~4 / Premium 1~5 — CLAUDE.md §2, 통합기획서 §3.3). Stage 3는 Pro 이상에만 노출.

## 요구사항

### 기능 요구사항
- [ ] **R1. Chroma 인프라**: docker-compose에 Chroma 컨테이너 추가 (데이터는 **bind mount** — named volume 금지, 2026-06-04 손실 사고 교훈 [[data_protection]]).
- [ ] **R2. BE 연동 계층**: `infrastructure/chroma/` 에 `ChromaClient` 인터페이스 + 실구현 + Mock (Stage 2 `LlmClient` 격리 패턴 답습, CLAUDE.md §3-2).
- [ ] **R3. 임베딩 생성 계층**: 공시 본문 → 임베딩 벡터. `infrastructure/llm/` 의 `EmbeddingClient` 인터페이스(MVP: Ollama 임베딩 모델 / 실서비스: Cloud 교체).
- [ ] **R4. `disclosure_embeddings` 컬렉션**: [[db_schema]] §4 metadata 스키마(disclosure_id, rcept_no, corp_code, stock_code, corp_name, disclosure_type, rcept_dt, sentiment) 준수, distance=cosine.
- [ ] **R5. 임베딩 적재(upsert)**: 신규 공시 자동 트리거(이벤트) + 과거 공시 백필. **멱등** — rcept_no/id 기준 중복 upsert 방지.
- [ ] **R6. 유사 공시 검색**: ① 동일 회사+동일 유형 최대 5건 ② 동일 유형 타사 최대 5건 → 최대 10건 ([[db_schema]] §4 쿼리 패턴).
- [ ] **R7. 결과 노출**: `AnalysisResponse.similarDisclosures`(현재 항상 null)를 `SimilarDisclosureItem` 리스트로 조립. `stage_reached=3` 기록.
- [ ] **R8. 신뢰도 가드**: 유사도 점수(similarity/distance)가 임계 미만이면 "유사 공시 없음" 처리 — 억지 매칭 금지(환각 방지, CLAUDE.md §6-6).

### 비기능 요구사항
- [ ] **R9. 격리**: analysis 도메인은 Chroma/임베딩 SDK에 직접 의존 금지 → `infrastructure/` 인터페이스 경유 (CLAUDE.md §3-2).
- [ ] **R10. 테스트**: Mock `ChromaClient`/`EmbeddingClient` 단위 테스트 필수. 통합 테스트는 Testcontainers PostgreSQL 유지(실 Chroma는 통합 테스트 제외 — 외부 의존 불안정).
- [ ] **R11. 외부 호출 가드**: Chroma/임베딩 호출에 타임아웃·재시도(지수 백오프) — Stage 2 `OllamaLlmClient` @Retryable 패턴 답습.

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(infrastructure, analysis), infra(docker-compose), scripts(data_collection — 임베딩 백필), frontend(disclosures 상세 — 유사 공시/차트 노출은 별도 FE Spec 가능)
- **신규 파일 (예상)**:
  - `docker-compose.yml` — Chroma service 추가 (bind mount)
  - `backend/build.gradle` — Chroma/임베딩 의존성 (LangChain4j 여부는 아래 "권장 구현 방향" 결정 사항)
  - `backend/.../infrastructure/chroma/ChromaClient.java` (interface) + 실구현 + `MockChromaClient.java`
  - `backend/.../infrastructure/chroma/ChromaProperties.java`
  - `backend/.../infrastructure/llm/EmbeddingClient.java` (interface) + 실구현 + Mock
  - `backend/.../analysis/services/Stage3RagService.java` (또는 `Stage3Analyzer`)
  - `application.yml` — `dartcommons.chroma.*`, `dartcommons.llm.embedding.*` 블록
- **수정 파일 (예상)**:
  - `backend/.../analysis/services/AnalysisOrchestrator.java` — L25 주석대로 Stage 3 단계 위임 추가 (Stage 2 완료 후 Stage3 트리거)
  - `backend/.../analysis/services/AnalysisQueryService.java` — similarDisclosures 조립
  - `backend/.../analysis/dto/SimilarDisclosureItem.java` — disclosure_id / similarity_score 필드 추가 검토 (record 불변 → 신규 정의)
  - `backend/.../analysis/dto/AnalysisResponse.java` — similarDisclosures 매핑 활성화
- **DB 변경**: PostgreSQL **Flyway 불필요** (벡터는 Chroma 외부 저장, FK 없음 — [[db_schema]] §2). 단 `stage_details(JSONB)`에 RAG 산출 저장 가능(스키마 변경 없음). → **단, KRX 주가 결합 시 `stock_prices` 보조 테이블이 새로 필요할 수 있음(아래 리스크 참조)**.
- **외부 계약**: 임베딩 모델 호출(Ollama `/api/embeddings` 또는 Cloud) + Chroma REST/SDK. DART/KRX/카카오 계약 변경 없음.

## 관련 패턴 / 과거 사례

- **과거 사례 (Step 0 learnings)**: `docs/solutions/` 없음. 단 아래 직접 참조 가능 자산 확인:
  - [[analysis-stage2-llm]] (Done) — 이벤트 기반 비동기(`@TransactionalEventListener(AFTER_COMMIT)` + `@Async`), record 스키마 강제 파싱, PromptGuard, 신뢰도 임계치 0.6 패턴. **Stage 3가 그대로 재사용**.
  - `infrastructure/llm/LlmClient.java` — provider 추상화 인터페이스 + `OllamaLlmClient`(RestClient 직접 호출, @Retryable 2회, 타임아웃) + `MockLlmClient`. **Chroma/임베딩 클라이언트의 본보기**.
  - `analysis/services/AnalysisOrchestrator.java` L25 — "Stage 3+ 후속 도입 시 본 Orchestrator가 추가 단계 호출 — 별도 Stage3Analyzer 위임" **이미 확장 지점 명시됨**.
  - `analysis/dto/SimilarDisclosureItem.java` — Stage 3 결과 타입 **이미 정의됨**(rcept_no, corp_name, rcept_dt, price_reaction_5d_pct). 단 disclosure_id·similarity_score 부재.
  - `analysis/entities/AnalysisResult.java` — `stage_reached`(short), `stage_details`(JSONB) 필드 **이미 존재** → Stage 3 산출 기록 가능.
  - [[feature_structure]] §1.1/§6/§7 — analysis 도메인이 "Chroma 임베딩/검색" 책임, `ChromaClient`·컬렉션 `disclosure_embeddings` 명문화.
- **임베딩 백필**: `scripts/data_collection/` Python 일괄 생성 경로가 통합기획서 §5.4에 규정 — 93,230건 기존 공시 임베딩 일괄 적재.

## 리스크 / 법적 검토

- **[기술 SSOT 불일치 — 중요]** CLAUDE.md §2·통합기획서는 "LangChain4j Chroma 통합"을 명시하나, **실제 build.gradle에 LangChain4j 의존성이 없음**. Stage 2는 LangChain4j 대신 Spring RestClient 직접 호출로 구현됨(`OllamaLlmClient`). → Stage 3에서 LangChain4j 도입 여부를 **명시적으로 결정**해야 함(아래 권장 방향). 어느 쪽이든 SSOT 문서 갱신 필요.
- **[데이터 미확보 — 범위 분리 권장]** 유사 공시의 "과거 5일 주가 반응"(`price_reaction_5d_pct`)에 필요한 **KRX 주가 시계열 테이블(`stock_prices`)이 부재**. [[db_schema]] §4도 "필요 시 보조 테이블 추가 검토"로 미확정. → **본 Spec 1차 범위에서 주가 반응 결합은 제외**하고, 유사 공시 검색(텍스트 유사도)까지만 구현. 주가 결합은 KRX 일별시세 동기화 + `stock_prices` 도입 후속 Spec으로 분리 권장.
- **[모델 미확정]** 임베딩 모델·차원 미정([[db_schema]] §4, 통합기획서 §6.3). MVP 로컬은 Ollama 임베딩 모델(예: `nomic-embed-text` 768 / `bge-m3` 1024) 벤치마크 후 확정 필요. 차원 변경 = 컬렉션 재생성 → **확정 전 대량 백필 금지**.
- **[데이터 보호]** Chroma 데이터도 PostgreSQL과 동일하게 **bind mount + 백업** 적용(named volume 사용 시 Docker reset로 91k 규모 재손실 위험 — [[data_protection]]).
- **[비용/시간]** 93,230건 임베딩 백필 — Ollama 로컬 RPS 측정 후 청크/병렬 전략 필요(Stage 2 백필 4.39초/건 선례). 모델 확정 전 시범 1,000건으로 정확도 검증 우선.
- **[자본시장법]** 유사 공시 "주가 반응" 노출 시 **투자 권유 표현 금지**(CLAUDE.md §7, 통합기획서 §11.1) — "과거 사례일 뿐, 미래 보장 아님" 면책 동반. 후속 FE 작업에서 강제.
- **[환각]** 유사도 낮은 결과를 억지로 노출하면 오인 유발 → R8 임계치 가드 필수.

## 권장 구현 방향

### 접근법 A (권장) — RestClient 직접 호출, Stage 2 선례 일관
- Chroma는 REST API(`/api/v1/...`)를, 임베딩은 Ollama `/api/embeddings`를 **Spring RestClient로 직접 호출** (`OllamaLlmClient`와 동일 패턴).
- **장점**: Stage 2와 코드 스타일 100% 일관, 옵션 완전 제어, 의존성 최소, 학습 곡선 없음. `OllamaLlmClient`의 @Retryable·타임아웃·예외 변환을 그대로 복제.
- **단점**: Chroma API 변경 시 직접 대응. (단 컬렉션 CRUD/query만 쓰므로 표면적 작음.)

### 접근법 B — LangChain4j(`langchain4j-chroma` + embedding) 도입
- CLAUDE.md/통합기획서 문구와 일치. 임베딩 스토어 추상화 제공.
- **단점**: Stage 2가 이미 RestClient 직접 호출로 LangChain4j를 **사용하지 않음** → 도입 시 코드 스타일 이원화. 버전/전이 의존성 부담. MVP 단계 과투자 우려.

> **권장: 접근법 A**. SSOT 문서(CLAUDE.md §2, 통합기획서)는 "LangChain4j" → "Chroma 연동(클라이언트 추상화)" 수준으로 표현 완화 갱신. 단 최종 결정은 /dc-tech-review 에서 확정.

### 범위 경계 (1차)
- **포함**: Chroma 인프라(R1) · BE 클라이언트 격리(R2) · 임베딩 생성(R3) · 컬렉션(R4) · 적재(R5, 신규 자동 + 시범 백필) · 텍스트 유사도 검색(R6) · 결과 노출(R7) · 신뢰도 가드(R8) · 격리/테스트/가드(R9~R11).
- **제외(후속 분리)**: ① KRX 5일 주가 반응 결합(`stock_prices` 부재) ② 93k 전건 백필(모델 확정 후) ③ FE 유사 공시/차트 UI(별도 FE Spec) ④ Stage 4 LLM 2차 판단.

### 확인 필요 (Tech Review/구현 전 결정)
1. 임베딩 모델·차원 — 벤치마크 후 확정 (확정 전 백필 보류).
2. LangChain4j 도입 여부(접근법 A vs B).
3. Chroma 배포 형태 — 로컬 docker-compose 컨테이너(MVP) / 실서비스 EC2 컨테이너 vs Chroma Cloud(통합기획서).
4. 유사도 임계치(distance/score cutoff) 기준값.
5. 임베딩 대상 — 공시 본문 전체 vs 청크(`content_text` 길이 분포 확인 필요).

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-29)

> 검토 방식: Spec의 코드 참조 주장을 실제 소스로 전수 대조(추정 배제). 아래 모두 코드 확인 완료.

### 검증된 Spec 주장 (코드 대조 결과)
- ✅ `build.gradle`에 LangChain4j/Chroma/embedding 의존성 **전무** → 접근법 A 타당성 확정.
- ✅ `infrastructure/llm/`에 `LlmClient`(IF) + `OllamaLlmClient`(@Retryable maxAttempts=3, JdkClient 타임아웃, HostWhitelist) + `MockLlmClient` + `OpenRouterLlmClient`(Cloud 선례) 존재 → 클라이언트 격리·재시도 패턴 **그대로 복제 가능**.
- ✅ `AnalysisOrchestrator` L25/L41 — Stage 2를 `@TransactionalEventListener(AFTER_COMMIT)` + `@Async("analysisExecutor")`로 트리거, "Stage3Analyzer 위임" 확장 지점 명시. Stage 3 연결은 L46 `ifPresent` 이후 체이닝.
- ✅ `SimilarDisclosureItem` record는 `rceptNo/corpName/rceptDt/priceReaction5dPct`만 보유 — `disclosure_id`·`similarity_score` 부재(Spec과 일치). record 불변 → 신규 정의 필요.
- ✅ `AnalysisResponse` L92 — `similar_disclosures`는 `null` 하드코딩 + `proPlus ? ... : null` 활성화 TODO **이미 배선됨**. 직렬화 키 `similar_disclosures`(snake_case) 확정. 활성화는 1줄 교체.
- ✅ `db_schema §4` — 컬렉션 `disclosure_embeddings`, distance=cosine, metadata 8필드, 이중 쿼리 패턴(동일회사+유형 / 동일유형 타사) 명문화. **Flyway 불필요**(Chroma 외부 저장, FK 없음 — §2).
- ✅ KRX `stock_prices` 테이블 부재 → `price_reaction_5d_pct` 충족 불가(Spec 범위 분리 판단 타당).

### 🔴 신규 발견 (Spec 미포착 — 선결 결정 필요)
- **[BLOCKER] 임베딩 원천 `content_text`가 항상 null.** `Disclosure.java` L18/L58 주석·필드 확인: 본문(`content_text`)·`attachment_url`은 **Stage 1 범위 밖이라 null 저장(후속 Spec)**. 수집 코드 어디에도 `setContentText`/population 경로 없음. 현재 DB에 채워진 텍스트는 `report_nm`(공시 제목) + `disclosure_type`(룰 분류)뿐.
  - → Spec R3(본문 임베딩)·R5(적재)·범위경계 "확인필요 5(본문 vs 청크)"가 모두 **존재하지 않는 본문**을 전제. 본문 확보 없이는 Stage 3가 의미 임베딩을 만들 수 없음.
  - → **결정 옵션**:
    - **(A1) 제목 임베딩 MVP**: `report_nm`(+`corp_name`+`disclosure_type`) 조합 텍스트를 임베딩. 본문 fetch 불요, 즉시 진행 가능. 유사도 품질 저하(제목 단문) 감수 — 시범 1k 정확도 검증으로 판단.
    - **(A2) 본문 fetch 선행**: DART 문서원문 API(`document.xml`/뷰어 파싱) → `content_text` 채우는 수집 확장을 **별도 선행 Spec**으로 분리 후 Stage 3 착수.
  - **권장: A1로 1차 범위 진입**(범위 최소·선례 일관), A2(본문 파이프라인)는 후속 Spec. 단 Tech Review 종료 전 사용자 확정 필요.
  - **✅ 결정(2026-06-29): A2 — 본문 fetch 선행.** `content_text`를 채우는 **수집 확장(DART 문서원문 API)을 선행 Spec으로 분리**한 뒤 Stage 3 임베딩 착수. 품질 우선·범위 증가 수용. → 본 Spec은 **인프라/격리(Wave 1)는 선행 Spec과 병렬 진행 가능**하나, 임베딩 적재·검색(#7·#11)은 본문 파이프라인 완료에 **블로킹**됨.

### ✅ 결정 사항 (2026-06-29 Tech Review 확정)
1. **임베딩 원천 = A2(본문 fetch 선행)**: 선행 Spec `disclosure-content-text-fetch`(가칭) 필요 — DART 문서원문 API → `content_text` 채움. **Stage 3 임베딩(#7·#11)의 선결 의존성**. (`content_text` 컬럼은 이미 존재 → DDL 불요, 수집 로직 확장만.)
2. **Chroma 연동 = 접근법 B(LangChain4j)**: `langchain4j` + `langchain4j-chroma` + 임베딩 모델 모듈 도입. CLAUDE.md §2·통합기획서 "LangChain4j Chroma" 문구와 **일치** → SSOT 완화 불요. 단 아래 주의:
   - **코드 스타일 이원화**: Stage 2는 RestClient 직접 호출(`OllamaLlmClient`) 유지, Stage 3만 LangChain4j 사용 → 혼재. `infrastructure/chroma`·`infrastructure/llm` 인터페이스(`ChromaClient`/`EmbeddingClient`)로 **LangChain4j를 감싸 도메인 격리**(CLAUDE.md §3-2)는 그대로 유지.
   - **버전·전이 의존성 관리**: `build.gradle`에 langchain4j BOM/버전 고정 + Spring Boot 3.x 호환 확인 필요(신규 검증 항목).
   - LangChain4j `EmbeddingModel`(Ollama) + `EmbeddingStore<TextSegment>`(Chroma) 조합으로 #4·#5·#7 구현.

### 아키텍처 분해
- **영향 레이어**: infra(docker-compose) · backend(infrastructure/chroma·infrastructure/llm·analysis) · backend/test · scripts(data_collection — 시범 백필) · frontend(별도 FE Spec 분리).
- **신규**: `infrastructure/chroma/{ChromaClient(IF), Chroma 실구현, MockChromaClient, ChromaProperties}` · `infrastructure/llm/{EmbeddingClient(IF), Ollama 임베딩 실구현, Mock}` · `analysis/services/Stage3RagService` · `analysis/dto/SimilarDisclosureItem` v2(또는 신규 record).
- **수정**: `AnalysisOrchestrator`(Stage 3 위임) · `AnalysisResponse`(L92 매핑 활성화 + stage_reached=3) · `application.yml`(chroma/embedding 블록) · `docker-compose.yml` · `build.gradle`(**접근법 B: langchain4j BOM + langchain4j-chroma + 임베딩 모델 모듈 추가**).

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 0 | **선행 Spec `disclosure-content-text-fetch`**(DART 문서원문 API → content_text 채움) — 별도 Spec 분리 | 선행 | BE | 상 | 선결(A2) |
| 1 | `build.gradle` LangChain4j BOM/버전 고정 + `langchain4j-chroma` + 임베딩 모델 모듈, Spring Boot 3.x 호환 검증 | backend/build | BE | 중 | - |
| 2 | Chroma 컨테이너 docker-compose 추가(**bind mount** ~/data/dartcommons-chroma, named volume 금지) | infra | BE | 하 | - |
| 3 | `ChromaProperties` + `application.yml dartcommons.chroma.* / llm.embedding.*`(url/timeout/retries/collection/model/dim) | backend/infra | BE | 하 | #2 |
| 4 | `ChromaClient`(IF) + LangChain4j `EmbeddingStore<TextSegment>` 래핑 실구현 + `MockChromaClient` (도메인 격리 §3-2) | backend/infra | BE | 중 | #1,#3 |
| 5 | `EmbeddingClient`(IF) + LangChain4j `EmbeddingModel`(Ollama) 래핑 실구현 + Mock(타임아웃·재시도 가드 R11) | backend/infra | BE(LLM) | 중 | #1,#3 |
| 6 | `disclosure_embeddings` 컬렉션 부트스트랩(생성/검증, cosine, metadata 스키마 R4) | backend/infra | BE | 중 | #4 |
| 7 | `SimilarDisclosureItem` v2 record(disclosure_id·similarity_score 추가, price 필드는 KRX 부재로 분리/제외) | backend/analysis/dto | BE | 하 | - |
| 8 | `Stage3RagService` — 본문 임베딩 upsert(rcept_no **멱등** R5) + 이중 쿼리 검색(R6) + 유사도 임계치 가드(R8) | backend/analysis | BE | 상 | #0,#4,#5,#6,#7 |
| 9 | `AnalysisOrchestrator` Stage 3 위임 연결(Stage 2 완료 후 체이닝, @Async 풀 영향 검토) | backend/analysis | BE | 중 | #8 |
| 10 | `AnalysisResponse.similarDisclosures` 매핑 활성화(L92 `proPlus ? ... : null`) + stage_reached=3 기록 | backend/analysis | BE | 하 | #8 |
| 11 | Mock 단위 테스트(Chroma/Embedding/Stage3RagService — 실 Chroma 통합 제외 R10) | backend/test | BE | 중 | #4,#5,#8 |
| 12 | 시범 1,000건 백필 스크립트(본문 확보 + 모델·차원 확정 후, 정확도 검증) | scripts/data_collection | BE/PY | 중 | #0,모델확정 |

### DB / 마이그레이션 영향
- **PostgreSQL Flyway 불필요** — 벡터는 Chroma 외부 저장, FK 없음(db_schema §2/§4 확인). `analysis_results.stage_details(JSONB)`·`stage_reached(short)` 기존 컬럼 재사용으로 RAG 산출 기록 가능 → **스키마 변경 0**.
- 단 A2(본문 fetch) 채택 시 `content_text` 채움은 컬럼만 이미 존재 → DDL 불요, 수집 로직 변경만. KRX 주가 결합은 본 범위 제외(`stock_prices` 후속).

### 외부 계약 영향
- 신규: Ollama `/api/embeddings`(임베딩) + Chroma REST(`/api/v1/...` collection CRUD·query). DART/KRX/카카오/LLM 분류 계약 **변경 없음**.
- 임베딩 **모델·차원 미확정** → 컬렉션 생성 차원 종속. 차원 변경 = 컬렉션 재생성이므로 **확정 전 대량 백필 금지**(시범 1k만).

### 리스크 & 법적 검토
- **[BLOCKER] content_text 부재**(상단) — 선결 결정 #0 없이는 #6·#7·#11 착수 불가.
- **[SSOT 정합 — 해소]** 접근법 B 확정으로 CLAUDE.md §2·통합기획서 "LangChain4j Chroma" 문구와 **일치** → 문서 완화 불요. 대신 **build.gradle 버전 고정 + Spring Boot 3.x 호환·전이 의존성 충돌**을 신규 검증 항목으로 추가(#1).
- **[코드 스타일 이원화]** Stage 2 RestClient 직접 호출 vs Stage 3 LangChain4j 혼재 → `ChromaClient`/`EmbeddingClient` 인터페이스로 LangChain4j를 감싸 도메인 격리는 유지하되, 신규 기여자 혼란 방지를 위해 클라이언트 머리주석에 "Stage 3는 LangChain4j 경유" 명시 필요.
- **[데이터 보호]** Chroma도 bind mount + 백업(named volume 금지 — [[feedback_data_protection]] 2026-06-04 91k 손실 교훈).
- **[자본시장법]** "주가 반응" 노출은 본 범위 제외(KRX 부재)이나, 후속 FE에서 "과거 사례·미래 보장 아님" 면책 강제(CLAUDE.md §7).
- **[환각]** R8 유사도 임계치 가드 필수 — cutoff 기준값(#7) 시범 데이터로 보정.

### 예상 wave 수
- **선행 (별도 Spec)**: #0 `disclosure-content-text-fetch` — DART 문서원문 → `content_text`. **본 Spec 임베딩의 선결**(A2 결정).
- **Wave 1 (인프라·격리, 선행과 병렬 가능)**: #1~#7 — LangChain4j 의존성·Chroma 컨테이너·`ChromaClient`/`EmbeddingClient`(LangChain4j 래핑)·컬렉션 부트스트랩·SimilarDisclosureItem v2. 본문 없이도 독립 머지 가능.
- **Wave 2 (검색·노출)**: #8~#11 — Stage3RagService(본문 임베딩 upsert·이중쿼리·임계치) + Orchestrator 연결 + Response 활성화 + Mock 테스트. **선행 Spec(#0) 완료 후 착수**.
- **Wave 3 (백필·검증, 선택)**: #12 시범 1k 백필 + 정확도 측정 → 모델/차원/임계치 확정.
