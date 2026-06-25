---
type: spec
status: Draft
created: 2026-06-24
updated: 2026-06-24
---

# Stage 3 RAG — Chroma 벡터 DB 도입 Spec

> 상태: **Draft** (dc-plan 생성)
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
