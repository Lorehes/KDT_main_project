---
type: spec
status: Approved
created: 2026-07-06
updated: 2026-07-06
---

# analysis Stage 4 — LLM 2차 분석(최종 판단) Spec

> 상태: Draft → **Approved** (2026-07-06, dc-tech-review 승인 + 결정 3건 확정)
> 마일스톤: Phase 1 후행 — Pro 티어 가치 완성 (Free 1~2 / **Pro 1~4** / Premium 1~5)
> 선행 의존: [[analysis-stage2-llm]] (Done) · [[analysis-stage3-rag-chroma]] (Done, 2026-07-06 프로덕션 가동 — 벡터 93,560건)

## 배경 / 목적

### 문제 정의

Stage 3 RAG가 가동돼 공시별 **과거 유사 공시 + 그 실측 주가 반응(D+1~D+5)**을 확보했지만, 이 근거를 종합해 "최종 판단"을 내리는 단계가 없다. 현재 Pro 응답의 `expected_reaction`·`rationale`은 **항상 null** — 엔티티 컬럼(`AnalysisResult.expectedReaction/rationale`, V5), 응답 DTO 필드, FE 렌더링(`EXPECTED_REACTION_CONFIG`, disclosures 상세)까지 모두 완비돼 있으나 이를 채우는 분석기가 부재하다.

통합기획서 §6 Stage 4 정의:
- **입력**: Stage 2 결과(sentiment/confidence/summary/요인) + Stage 3 유사 공시 + 유사 공시의 실측 주가 반응
- **처리**: 1차 분석 + 과거 사례를 종합하는 프롬프트로 LLM 재호출
- **출력**: `expected_reaction`(UP/FLAT/DOWN) + `rationale`(판단 근거 자연어)

### 페르소나 / BM 티어

- 페르소나 B(적극적 정보 수집)·D(다종목 장기) — "과거 유사 사례를 종합한 방향성 판단"
- **PRO+ 전용** (통합기획서 §3.3). Free는 Stage 2까지, Stage 4 필드는 응답 화이트리스트에서 이미 티어 게이팅됨(`AnalysisResponse.from` proPlus 분기).

## 요구사항

### 기능 요구사항
- [ ] **R1. Stage4Output record** — `expectedReaction`(UP/FLAT/DOWN) + `rationale`(String) + `confidence`(BigDecimal, 재평가). `Stage2Output` 패턴 답습, 스키마 강제 파싱(CLAUDE.md §6-6, 환각 방지).
- [ ] **R2. LlmClient.classifyStage4** — 인터페이스에 메서드 추가(기존 주석이 이미 예고: "Stage 3~5는 별도 메서드 classifyStage4"). 각 구현체(Ollama/OpenRouter/Mock) 파서 동기 구현.
- [ ] **R3. Stage4PromptBuilder** — Stage 2 결과 + 유사 공시 목록 + 각 유사 공시의 D+1~D+5 실측 등락을 프롬프트로 구성. 원문 인용 수치(회사명/날짜/등락률)는 룰 기반 값 그대로 주입(LLM 변형 금지, CLAUDE.md §4).
- [ ] **R4. Stage4Analyzer** — `analyze(disclosureId)`: Stage 2 완료된 AnalysisResult + Stage3RagService.findSimilar + PriceReactionForecastService 결과 종합 → LLM 호출 → **기존 AnalysisResult UPDATE**(expectedReaction/rationale/stageReached=4). Stage 2 패턴(재시도 1회 + PromptGuard + 실패 시 stage 유지) 답습.
- [ ] **R5. 파이프라인 연결** — `AnalysisOrchestrator.onDisclosureCollected`에서 Stage 3 upsert 다음에 Stage 4 트리거. Stage 4 실패는 warn 후 계속(Stage 2 결과·알림 발행 차단 금지 — 기존 Stage 3 실패 격리 패턴 답습).
- [ ] **R6. PromptGuard 확장** — `rationale`에도 자본시장법 금지 표현 가드 적용(매수/매도 권유 차단, §7). 신뢰도 임계 미만 시 withheld 유지 — Stage 4 판단도 보류 대상.
- [ ] **R7. 백필 잡** — 이미 Stage 2 완료된 기존 분석(19,609건+)에 Stage 4 소급 적용하는 관리자 잡. `AnalysisBackfillService`/`EmbeddingBackfillService` 커서 재개 패턴 답습. **없으면 신규 공시만 Stage 4 적용되고 과거는 영원히 stage_reached=2.**

### 비기능 요구사항
- [ ] **R8. 격리** — analysis 도메인은 Stage 3(Chroma)·주가(stocks) 결과를 기존 서비스 경유로만 사용. 신규 외부 의존 없음(Stage 4는 LLM 재호출만).
- [ ] **R9. 테스트** — Stage4Analyzer 단위(Mock LlmClient) + 백필 Testcontainers IT(커서 전진·UPDATE 검증). Mock DB 금지(CLAUDE.md §6-6). AnalysisResponseTest PRO 케이스에 expected_reaction/rationale 채워진 분기 추가.
- [ ] **R10. LLM 예산 가드** — Stage 4는 공시당 LLM 호출을 1회 추가 → 프로덕션 OpenRouter `:free`(일 1,000건 상한, 크레딧0이면 50건) 소진 리스크. **결정 필요**(아래 리스크 참조).

## 영향 범위 (조사 결과)

### 영향 파일 (신규)
- `backend/.../analysis/dto/Stage4Output.java` — 신규 record
- `backend/.../analysis/services/Stage4Analyzer.java` — 신규 분석기
- `backend/.../analysis/services/Stage4PromptBuilder.java` — 신규 프롬프트 빌더
- `backend/.../analysis/services/Stage4BackfillService.java` + 컨트롤러 — 신규 (R7)

### 영향 파일 (수정)
- `backend/.../infrastructure/llm/LlmClient.java` — `classifyStage4` 추가
- `backend/.../infrastructure/llm/{Ollama,OpenRouter,Mock}LlmClient.java` — 구현 3종
- `backend/.../analysis/services/AnalysisOrchestrator.java` — Stage 4 트리거 (line 49~57 영역)
- `backend/.../analysis/services/PromptGuard.java` — rationale 가드
- `backend/.../analysis/repositories/AnalysisResultRepository.java` — Stage 4 대상 조회(stage_reached=2, 백필용)

### 변경 불필요 (이미 존재 — 확인 완료)
- **DB**: `AnalysisResult.expectedReaction/rationale/stageReached` 컬럼 V5에 존재 → **Flyway 불필요**
- **응답 DTO**: `AnalysisResponse` expected_reaction/rationale 필드 + proPlus 티어 게이팅 존재
- **FE**: `disclosures/[id]/page.tsx` reactionCfg 렌더링 + rationale 표시, `EXPECTED_REACTION_CONFIG` 매핑 존재 → **FE 변경 최소/무**

### 외부 계약
- DART/KRX/카카오: 변경 없음. LLM: 프롬프트 1종 추가(모델 계약 불변).

## 관련 패턴 / 과거 사례

- **Stage 2 분석기 전 과정**: `Stage2Analyzer`(재시도 1회 + PromptGuard + UPSERT) — Stage 4가 그대로 답습, 단 INSERT가 아닌 **UPDATE**(공시당 AnalysisResult 1건 UNIQUE).
- **Stage 3 실패 격리**: `AnalysisOrchestrator` line 51~55 try-warn-continue — Stage 4도 동일 격리.
- **백필 커서 재개**: `EmbeddingBackfillService`(방금 완료, 93,560건) — Stage 4 백필 잡의 직접 템플릿.
- **유사 공시 + 주가 반응 조립**: `AnalysisQueryService.getByDisclosureId`(findSimilar + forecast) — Stage4PromptBuilder 입력 조립 로직 재사용 가능.
- (docs/solutions 디렉터리 없음 — learnings 검색 생략)

## 리스크 / 법적 검토

- **LLM 예산 (핵심 결정)**: Stage 4는 공시당 호출 2배. 프로덕션이 OpenRouter `nvidia/nemotron-3-super-120b:free`(일 1,000건 상한). 신규 공시 폴링량 + Stage 2 + Stage 4 합산이 상한을 넘을 수 있음. 선택지:
  - (A) **전건 Stage 4** — 단순하나 예산 초과 위험. 유료 전환($10 크레딧 → 일 1,000건 유지, 모델은 `LLM_MODEL` env 교체) 병행 검토.
  - (B) **조건부 Stage 4** — Stage 2 신뢰도 낮음 or 유사 공시 표본 충분한 건만. 예산 절감 but 로직 복잡.
  - (C) **유사 공시 없으면 skip** — Stage 3 표본 0이면 Stage 4 근거 부재 → Stage 2로 종결. 자연스러운 예산 가드. **권장 기본값**.
- **자본시장법 (§11.1)**: `rationale`가 "매수/매도 추천"으로 흐르지 않도록 PromptGuard 필수 확장. "예상 반응(UP/FLAT/DOWN)"은 방향성 정보이지 투자 권유가 아님을 프롬프트에 명시.
- **환각**: 유사 공시 주가 반응은 실측 데이터(룰 기반) 주입 — LLM이 수치를 만들지 않도록 프롬프트에서 "제공된 수치만 사용" 강제.
- **withheld 일관성**: Stage 2에서 withheld된 분석은 Stage 4를 돌리지 않거나(권장), 돌려도 withheld 유지 — "판단 보류"인데 방향성 단정하는 모순 차단.

## 권장 구현 방향

**접근 (C) 기반 + Stage 2 패턴 답습**:

1. `Stage4Output` record + `LlmClient.classifyStage4` + Mock/Ollama/OpenRouter 파서
2. `Stage4PromptBuilder` — Stage 2 결과 + 유사 공시(최대 10건) + 각 D+1~D+5 실측 등락 조립
3. `Stage4Analyzer.analyze()` — **유사 공시 표본 0이면 skip**(stage 2 종결), 아니면 LLM 호출 → PromptGuard → AnalysisResult UPDATE(stage_reached=4). withheld 건 skip.
4. `AnalysisOrchestrator`에 Stage 3 upsert 후 Stage 4 트리거(실패 격리)
5. Stage 4 백필 잡(관리자 API, 커서 재개) — 기존 stage_reached=2 && 유사표본 보유 대상
6. 테스트: Stage4Analyzer 단위 + 백필 IT + AnalysisResponseTest PRO 분기

**규모 예상**: 신규 4파일 + 수정 6파일, DB·FE 변경 거의 없음 → Stage 2 대비 경량. 백필은 Stage 3처럼 로컬 실행 + (LLM은 서버 OpenRouter 공유이므로 예산 주의) — **백필 시 예산 소진 집중** 주의, 야간 분할 또는 유료 전환 후 실행 권장.

**미해결(Tech Review에서 확정)**:
- 예산 전략 A/B/C 최종 선택 (사용자 결정 필요)
- Stage 4 백필을 로컬에서 돌릴지(로컬 OpenRouter 키 별도 필요) 서버에서 야간 분할할지
- `confidence` 재평가 여부 — Stage 4가 Stage 2 confidence를 덮어쓸지, 별도 보존할지

## Tech Review (dc-tech-review · 2026-07-06)

### 아키텍처 분해

- **영향 레이어**: backend(analysis, infrastructure/llm)만. frontend·DB·외부 계약 변경 **없음**(다운스트림 전부 기존재 확인).
- **재사용 자산 (조사로 확정)**:
  - `AnalysisResult.expectedReaction/rationale/stageReached` 컬럼 — V5 존재, **Flyway 불필요**.
  - `AnalysisResponse.from` proPlus 분기 — expected_reaction/rationale 티어 게이팅 존재.
  - FE `disclosures/[id]/page.tsx` reactionCfg + rationale 렌더 + `EXPECTED_REACTION_CONFIG` — **FE 무변경**.
  - **`AnalysisJob`(stage 컬럼) + `ReanalysisService`(createAndStartAsync/커서/`stage` 파라미터)** — Stage 4 백필의 직접 재사용 대상. 신규 Job 엔티티·서비스 골격 불필요 → R7 대폭 경량화.
  - `Stage3RagService.findSimilar(id)` + `PriceReactionForecastService.forecast(similar, days)` — Stage 4 입력 조립 로직 그대로 재사용(`AnalysisQueryService`에 이미 조합됨).
- **신규**: `Stage4Output`(dto) · `Stage4PromptBuilder` · `Stage4Analyzer` · `LlmClient.classifyStage4` + 구현 3종 파서.
- **수정**: `AnalysisOrchestrator`(트리거) · `PromptGuard`(rationale 가드) · `ReanalysisService`(stage=4 분기) · `AnalysisResultRepository`(Stage4 대상 조회).

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `Stage4Output` record (expectedReaction/rationale/confidence) — Stage2Output 패턴 | backend/analysis | BE | 하 | - |
| 2 | `LlmClient.classifyStage4` + Ollama·OpenRouter·Mock 파서(`Stage4OutputRaw`) 3종 | backend/infra(llm) | BE(LLM) | 중 | #1 |
| 3 | `PromptGuard` rationale 가드 — String 가드 메서드 추출 + Stage4 sanitize 오버로드 | backend/analysis | BE | 중 | - |
| 4 | `Stage4PromptBuilder` — Stage2결과+유사공시+D1~D5 실측등락 조립(실측 수치 주입, LLM 변형 금지) | backend/analysis | BE | 중 | #1 |
| 5 | `Stage4Analyzer.analyze()` — 유사표본0/withheld skip + LLM호출 + 가드 + **AnalysisResult UPDATE**(stage=4) | backend/analysis | BE | 상 | #1,#2,#3,#4 |
| 6 | `AnalysisOrchestrator` Stage3 upsert 후 Stage4 트리거(실패 격리) | backend/analysis | BE | 하 | #5 |
| 7 | Stage4 백필 — `ReanalysisService` stage=4 분기(AnalysisJob 재사용) + 컨트롤러 엔드포인트 | backend/analysis | BE | 중 | #5 |
| 8 | 테스트 — Stage4Analyzer 단위(Mock) + 백필 Testcontainers IT + AnalysisResponseTest PRO 분기 | backend/analysis | BE | 중 | #5,#7 |

### DB / 마이그레이션 영향

- **Flyway 마이그레이션 불필요** — `expected_reaction`(varchar20)·`rationale`(text)·`stage_reached`(smallint) 컬럼 V5 기존재, `analysis_jobs.stage` 컬럼도 기존재. db_schema.md와 대조 확인 완료.
- 인덱스: Stage 4 백필의 `WHERE stage_reached=2` 조회가 풀스캔이면 부분 인덱스 검토 — 단 1회성 관리자 잡이라 커서 페이지네이션(id > lastId)으로 충분, **신규 인덱스 불필요**(Stage 3 백필과 동일 판단).

### 외부 계약 영향

- DART/KRX/카카오: **변경 없음**.
- LLM: 프롬프트 1종(Stage 4) 추가. 모델 계약 불변(`response_format=json_object`, `LLM_MODEL` env 그대로). OpenRouter/Ollama 양쪽 `Stage4OutputRaw` 파서 동기 필요(Stage2 패턴 답습).

### 리스크 & 법적 검토

- **[P0 결정] LLM 예산** — 공시당 호출 2배. 프로덕션 OpenRouter `:free`(일 1,000건, 크레딧0시 50건). **접근 (C) 유사표본0 skip을 기본**으로 하되, 신규 공시 폴링 실측량 확인 후 초과 위험 시 $10 크레딧(일 1,000 유지) 또는 조건부(B) 병행. → **사용자 결정 필요**.
- **[P0 자본시장법 §11.1]** `rationale`가 "매수/매도 권유"로 흐르면 위법 소지. 현재 `PromptGuard.FORBIDDEN_PATTERNS`는 `Stage2Output` 전용 시그니처 → **String 인자 가드 메서드 추출**(카드 #3)해 rationale에 동일 적용. "예상 반응 UP/FLAT/DOWN은 방향성 정보이지 투자 권유 아님"을 프롬프트에 명시(L1) + 후처리 가드(L2) 이중 방어.
- **[P1 환각]** 유사 공시 주가 반응은 실측(룰 기반) 수치를 프롬프트에 주입하고 "제공된 수치만 사용" 강제 — LLM이 등락률을 지어내지 않도록.
- **[P1 withheld 일관성]** Stage2 withheld 건은 Stage4 skip(권장) — "판단 보류"인데 방향 단정하는 모순 차단. 카드 #5 skip 규칙에 포함.
- **[P2 재분석 cascade]** `ReanalysisService` 주석의 feedbacks cascade 유실 경고 — Stage4 백필은 **UPDATE(덮어쓰기)**라 row 삭제 없음 → feedbacks FK 안전. 신규 INSERT/DELETE 아님을 카드 #7에서 보장.

### 예상 wave 수

- **Wave 1 (신규 공시 Stage 4 파이프라인)**: 카드 #1~#6 — 이후 수집되는 공시에 Stage 4 자동 적용.
- **Wave 2 (백필 + 테스트)**: 카드 #7~#8 — 기존 stage=2 분석 소급. 백필 실행은 예산 집중 소모라 야간 분할 또는 유료 전환 후 권장(Stage 3 백필 교훈).

### 확정 결정 (2026-07-06, 사용자 승인)

1. **예산 전략 = (C)** — 유사 공시 표본 0이면 Stage 4 skip(Stage 2 종결). 폴링 실측 후 초과 위험 시 $10 크레딧/조건부(B) 재검토.
2. **confidence = Stage 2 값 보존** — Stage 4는 `expected_reaction`+`rationale`+`stage_reached`만 UPDATE. 신뢰도 소스 단일화(Stage4Output의 confidence는 응답 파싱용으로만 수신, 저장하지 않음).
3. **백필 = 서버 야간 분할** — LLM이 외부 API(OpenRouter)라 로컬 실행 이점 없음. `ReanalysisService` 커서 재개로 일 예산 소진 시 중단 → 다음날 재개 반복. 일 1,000건 상한 기준 대상량에 따라 수일 소요 허용.
