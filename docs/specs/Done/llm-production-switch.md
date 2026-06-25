---
type: spec
status: Done
created: 2026-06-25
updated: 2026-06-25
---

# 프로덕션 LLM 전환 Spec (OpenRouter)

> 상태: Approved → **Done** (2026-06-25, 구현 완료 08af22b)
> 선행 Spec: [[analysis-stage2-llm]] (Done) — LlmClient 인터페이스·MockLlmClient·OllamaLlmClient 구현 완료

## 배경 / 목적

- **문제**: `application.yml:140`에 `provider: ${LLM_PROVIDER:mock}` — 기본값이 **mock**. 환경변수 미주입 시 프로덕션에서도 `MockLlmClient`가 동작해 실제 LLM 분류 결과 0%.
- **해결**: **OpenRouter** Cloud LLM을 사용하는 `OpenRouterLlmClient`를 추가하고 `LLM_PROVIDER=openrouter`로 전환. Stage 2 LLM 분류가 실제 작동해야 서비스 핵심 가치(호재/악재 분류) 제공 가능.
- **OpenRouter 선택 이유**: 단일 API 키로 수십 개 LLM Provider(Anthropic·Google·Meta·Mistral 등)를 모델명 하나로 교체 가능. 서버에 Ollama를 설치할 필요 없어 t3.medium(4GB)으로 배포 가능 — Ollama 대비 서버 비용 절감.
- **BM 티어**: 전 티어 (Free 1~2 / Pro 1~4 / Premium 1~5) — Stage 2는 모든 티어에 적용.

## OpenRouter API 개요

| 항목 | 값 |
|------|-----|
| Base URL | `https://openrouter.ai/api/v1` |
| 인증 | `Authorization: Bearer ${OPENROUTER_API_KEY}` |
| 엔드포인트 | `/chat/completions` (OpenAI Chat Completions 호환) |
| 요청 형식 | `{ model, messages:[{role,content}], response_format:{type:"json_object"} }` |
| 응답 형식 | `{ choices:[{ message:{ content: "JSON 문자열" } }] }` |
| 모델 교체 | 모델명(`LLM_MODEL`)만 바꾸면 코드 무수정 교체 가능 |
| 권장 무료 모델 | `google/gemma-3-4b-it:free`, `meta-llama/llama-3.1-8b-instruct:free` |
| 권장 유료 모델 | `google/gemini-flash-1.5` ($0.075/1M), `anthropic/claude-haiku-4-5-20251001` |

## 현황 분석

```
infrastructure/llm/
├── LlmClient.java            ← 인터페이스 (classifyStage2 — 변경 없음)
├── LlmProperties.java        ← @ConfigurationProperties — apiKey 필드 추가 필요
├── OllamaLlmClient.java      ← @ConditionalOnProperty(havingValue="ollama") — 유지
├── MockLlmClient.java        ← @ConditionalOnProperty(havingValue="mock")   — 유지
└── OpenRouterLlmClient.java  ← 신규 추가 (@ConditionalOnProperty(havingValue="openrouter"))
```

`LlmProperties`에 `apiKey` 필드 없음 → 추가 필요.
`application.yml` `base-url`이 Ollama 전용 (`${OLLAMA_BASE_URL:http://localhost:11434}`) → OpenRouter URL로 분리 필요.

## 요구사항

### BE 코드

- [ ] **R1** `LlmProperties` — `apiKey` 필드 추가
  ```java
  @ConfigurationProperties(prefix = "dartcommons.llm")
  public record LlmProperties(
      String provider,
      String baseUrl,
      String apiKey,          // 신규 — OpenRouter API 키 (환경변수 주입)
      String model,
      int timeoutMs,
      int maxRetries,
      double confidenceThreshold
  ) {}
  ```

- [ ] **R2** `OpenRouterLlmClient.java` 신규 — `OllamaLlmClient`와 동일 패턴 준수
  ```java
  @Component
  @ConditionalOnProperty(prefix = "dartcommons.llm", name = "provider", havingValue = "openrouter")
  public class OpenRouterLlmClient implements LlmClient {
      // RestClient 직접 호출 (OllamaLlmClient 패턴 답습)
      // HostWhitelist.verify(props.baseUrl(), "OpenRouterLlmClient")
      // Authorization: Bearer {apiKey} 헤더
      // POST /chat/completions
      // @Retryable(retryFor=RestClientException.class, maxAttempts=3, backoff=지수백오프)
  }
  ```

  **요청 바디 구조**:
  ```json
  {
    "model": "${LLM_MODEL}",
    "messages": [
      { "role": "user", "content": "<Stage2PromptBuilder 출력>" }
    ],
    "response_format": { "type": "json_object" },
    "temperature": 0.2,
    "max_tokens": 400
  }
  ```

  **응답 파싱**:
  ```java
  // choices[0].message.content → JSON 문자열 → Stage2OutputRaw record 파싱
  // OllamaLlmClient의 Stage2OutputRaw.toStage2Output() 동일 로직 재사용
  record OpenRouterResponse(List<Choice> choices) {}
  record Choice(Message message) {}
  record Message(String content) {}
  ```

- [ ] **R3** `application.yml` LLM 섹션 업데이트
  ```yaml
  dartcommons:
    llm:
      provider: ${LLM_PROVIDER:mock}
      base-url: ${LLM_BASE_URL:https://openrouter.ai/api/v1}   # Ollama/OpenRouter 공용
      api-key: ${OPENROUTER_API_KEY:}                           # mock/ollama 시 빈 값 무해
      model: ${LLM_MODEL:google/gemma-3-4b-it:free}             # 기본 모델
      timeout-ms: ${LLM_TIMEOUT_MS:30000}                       # Cloud LLM은 Ollama보다 빠름
      max-retries: ${LLM_MAX_RETRIES:2}
      confidence-threshold: ${LLM_CONFIDENCE_THRESHOLD:0.6}
  ```

- [ ] **R4** `OllamaLlmClient` — `apiKey` 필드 추가로 인한 컴파일 오류 없는지 확인 (record 필드 추가이므로 무관)

### 환경변수 (`.env.prod`)

- [ ] **R5** `.env.prod`에 추가
  ```
  LLM_PROVIDER=openrouter
  LLM_BASE_URL=https://openrouter.ai/api/v1
  OPENROUTER_API_KEY=sk-or-v1-...
  LLM_MODEL=google/gemma-3-4b-it:free
  LLM_TIMEOUT_MS=30000
  ```

### 품질 검증

- [ ] **R6** 분석 품질 검증 — 공시 10건 수동 채점 (호재/악재 정확도 70% 이상 목표)
- [ ] **R7** 무료 모델 품질 미달 시 유료 모델 교체 — `LLM_MODEL` 환경변수만 변경, 코드 무수정

## 영향 범위 (조사 결과)

- **영향 레이어**: backend(`infrastructure/llm/`)
- **신규 파일**:
  - `backend/src/main/java/com/dartcommons/infrastructure/llm/OpenRouterLlmClient.java`
- **수정 파일**:
  - `backend/src/main/java/com/dartcommons/infrastructure/llm/LlmProperties.java` — `apiKey` 필드 추가
  - `backend/src/main/resources/application.yml` — LLM 섹션 업데이트
  - `.env.prod` / `.env.prod.template`
- **DB 변경**: 없음
- **외부 계약**: OpenRouter API 키 발급 (openrouter.ai 가입 → API Keys 발급, 즉시 가능)
- **배포 인프라 영향**: Ollama 컨테이너 불필요 → `docker-compose.prod.yml`에서 Ollama 서비스 제거 가능, EC2 t3.medium(4GB)으로 충분 ([[deployment-infra-docker-cicd]] Spec과 연계)

## 관련 패턴 / 과거 사례

- `OllamaLlmClient.java` — RestClient 직접 호출, @Retryable(3회, 지수백오프 1s→8s), HostWhitelist, `Stage2OutputRaw` record 파싱. **OpenRouterLlmClient가 동일 패턴 답습**.
- `Stage2PromptBuilder.java` — 프롬프트는 변경 없음. OpenRouter가 한국어 명령을 잘 이해하므로 그대로 사용.
- `MockLlmClient.java` — `@ConditionalOnProperty(matchIfMissing=false)` 패턴 확인. 테스트에서 `LLM_PROVIDER=mock` 명시 유지.
- `analysis-stage2-llm` (Done) — Spec NF4: 통합 테스트에서 LLM은 Mock 권장. → OpenRouterLlmClient는 단위 테스트(Mock 주입)로 커버.

## 리스크 / 법적 검토

| 리스크 | 발생 확률 | 대응 |
|------|---------|------|
| 무료 모델 품질 미달 (신뢰도 60% 미만 다수) | 中 | `LLM_MODEL` 환경변수로 즉시 교체. 유료 전환 전 10건 수동 채점 필수 |
| 무료 모델 Rate Limit (요청/분 제한) | 中 | 1분 폴링 + analysisExecutor pool 2~4개 → 분당 수십 건 이하. 초과 시 @Retryable로 자동 재시도 |
| OpenRouter 서비스 장애 | 低 | Stage2Analyzer silent fail → stage_reached=1 유지, 다음 백필 재분석 가능 |
| API 키 유출 | 절대 방지 | 환경변수만 주입, 코드·git 커밋 금지 (CLAUDE.md §7). `.env.prod`는 `.gitignore` 등록 |
| 토큰 비용 폭주 | 低 | 무료 모델 사용 시 0원. 유료 전환 시 일별 cap 환경변수 후속 (분석 건수 제한, 통합기획서 §6.2) |
| 투자 권유 표현 출력 | 中 | PromptGuard(L2) 이중 가드 유지 — OpenRouter 모델도 동일하게 적용됨 (CLAUDE.md §7) |

## 권장 구현 방향

**접근법: OpenRouterLlmClient 신규 추가** — `OllamaLlmClient`와 동일 패턴, RestClient 직접 호출.

```
OllamaLlmClient  →  POST /api/generate          (Ollama 전용 포맷)
OpenRouterLlmClient → POST /chat/completions     (OpenAI 호환 포맷)
```

두 클라이언트가 동일한 `LlmClient` 인터페이스를 구현하므로 `Stage2Analyzer`·`AnalysisOrchestrator`·테스트 코드 변경 없음.

**모델 선택 전략**:
1. 개발/검증: `google/gemma-3-4b-it:free` (무료, 한국어 이해 양호)
2. 품질 미달 시: `meta-llama/llama-3.1-8b-instruct:free` (무료, 영어 프롬프트 효과적)
3. 프로덕션 품질 확보: `google/gemini-flash-1.5` (유료·저렴, 다국어 우수)
4. 최고 품질 필요 시: `anthropic/claude-haiku-4-5-20251001`

**테스트 전략**:
- 단위 테스트: `MockLlmClient` 유지 (기존 통합 테스트 변경 없음)
- `OpenRouterLlmClient` 자체: `RestClientException` Mock 주입 단위 테스트 신규 작성
- 실 API 연동 검증은 수동 스모크 테스트 (공시 10건)

## Tech Review (dc-tech-review · 2026-06-25)

### 아키텍처 분해

- **영향 레이어**: `backend/infrastructure/llm/` (신규 1파일 + 수정 3파일) + `backend/shared/util/` (수정 1파일)
- **신규**: `OpenRouterLlmClient.java`
- **수정**: `LlmProperties.java` (apiKey 필드), `HostWhitelist.java` (openrouter.ai 추가 — **Spec 미명시, Tech Review 발견**), `application.yml` (LLM 섹션)
- **무변경**: `LlmClient.java` (인터페이스), `MockLlmClient.java`, `Stage2Analyzer.java`, `AnalysisOrchestrator.java`, 테스트 코드

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `HostWhitelist.java` — `PROD_ALLOWED`에 `"openrouter.ai"` 추가 | backend/shared/util | BE | 하 | - |
| 2 | `LlmProperties.java` — `apiKey` 필드 추가 (record 컴포넌트) | backend/infrastructure/llm | BE | 하 | - |
| 3 | `OpenRouterLlmClient.java` 신규 구현 | backend/infrastructure/llm | BE | 중 | #1 #2 |
| 4 | `application.yml` LLM 섹션 업데이트 | backend/resources | BE | 하 | #2 |

### 카드별 구현 메모

**Card #1 — HostWhitelist (Spec 미명시, 필수)**

`OllamaLlmClient`는 생성자에서 `HostWhitelist.verify(props.baseUrl(), "OllamaLlmClient")`를 호출한다.
`OpenRouterLlmClient`도 동일 패턴을 답습해야 하는데, 현재 `PROD_ALLOWED = {"opendart.fss.or.kr", "data.krx.co.kr", "alimtalk-api.kakao.com"}`에 `openrouter.ai`가 없다.
미추가 시 부팅 시점에 `IllegalStateException` 발생 → 애플리케이션 기동 실패 (P0).

```java
// HostWhitelist.PROD_ALLOWED에 추가
"openrouter.ai"   // OpenRouter Cloud LLM API
```

**Card #2 — LlmProperties**

`LlmProperties`는 `@ConfigurationProperties` record. `apiKey` 컴포넌트 추가 시 Spring이 `api-key: ${OPENROUTER_API_KEY:}` YAML 값을 자동 바인딩.
테스트에서 `new LlmProperties(...)` 직접 생성 없음(grep 확인) → 컴파일 오류 없음.
`OllamaLlmClient`는 `props.apiKey()` 미사용 → 기존 코드 수정 불필요.

```java
@ConfigurationProperties(prefix = "dartcommons.llm")
public record LlmProperties(
    String provider,
    String baseUrl,
    String apiKey,          // 신규 — mock/ollama 시 빈 값 무해
    String model,
    int timeoutMs,
    int maxRetries,
    double confidenceThreshold
) {}
```

**Card #3 — OpenRouterLlmClient**

`OllamaLlmClient` 패턴 답습. 차이점만 명시:

| 항목 | OllamaLlmClient | OpenRouterLlmClient |
|------|-----------------|---------------------|
| 엔드포인트 | `POST /api/generate` | `POST /chat/completions` |
| 인증 | 없음 | `Authorization: Bearer {apiKey}` |
| 요청 바디 | `{model, prompt, format, stream, think, options}` | `{model, messages:[{role,content}], response_format, temperature, max_tokens}` |
| 응답 파싱 | `res.response()` (문자열) | `res.choices()[0].message().content()` |
| 파싱 record | `OllamaGenerateResponse` | `OpenRouterResponse(List<Choice>)` |

`Stage2OutputRaw` + `toStage2Output()` 로직은 두 클라이언트 공통 — 동일 private record로 복사.
`@ConditionalOnProperty(havingValue = "openrouter")`.
`@Retryable(retryFor = RestClientException.class, maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2.0, maxDelay = 8_000))` 동일.

**Card #4 — application.yml**

환경변수명 변경: `OLLAMA_BASE_URL` → `LLM_BASE_URL` (Ollama/OpenRouter 공용).
`OLLAMA_BASE_URL`은 application.yml 외에 docker-compose.yml 미사용 확인됨 → 안전하게 교체 가능.

```yaml
dartcommons:
  llm:
    provider: ${LLM_PROVIDER:mock}
    base-url: ${LLM_BASE_URL:https://openrouter.ai/api/v1}   # OLLAMA_BASE_URL → LLM_BASE_URL
    api-key: ${OPENROUTER_API_KEY:}                           # mock/ollama 시 빈 값 무해
    model: ${LLM_MODEL:google/gemma-3-4b-it:free}             # 기본 무료 모델
    timeout-ms: ${LLM_TIMEOUT_MS:30000}                       # Cloud는 Ollama보다 빠름(60s→30s)
    max-retries: ${LLM_MAX_RETRIES:2}
    confidence-threshold: ${LLM_CONFIDENCE_THRESHOLD:0.6}
```

### DB / 마이그레이션 영향

- **없음** — LLM provider 교체는 순수 코드·설정 변경. `analysis_results.model_name` 컬럼에 모델명 저장되나 스키마 변경 없음 (VARCHAR 유연).

### 외부 계약 영향

- **OpenRouter API**: OpenAI Chat Completions 호환 포맷(`/chat/completions`). `response_format: {type: "json_object"}` 지원 확인 완료 (OpenRouter 공식 문서 기준).
- **PromptGuard, Stage2PromptBuilder**: 변경 없음 — 프롬프트 텍스트 동일, L2 가드 동일 적용.
- **테스트**: `Stage2AnalyzerIntegrationTest`가 `dartcommons.llm.provider=mock`으로 강제 → 기존 테스트 변경 불필요. `OpenRouterLlmClient` 단위 테스트는 `RestClientException` Mock 주입으로 별도 작성 (통합 테스트 대상 아님 — Spec §테스트 전략).

### 리스크 & 법적 검토

| 리스크 | 심각도 | 대응 |
|--------|--------|------|
| HostWhitelist `openrouter.ai` 미추가 → 부팅 실패 | **P0** | Card #1 우선 구현 (의존성 상단) |
| API 키 유출 → `.env.prod` git 커밋 | **P0** | `.gitignore` 등록 확인, GitHub Secrets 주입 |
| 무료 모델 Rate Limit → 분석 병목 | P2 | 1분 폴링 + analysisExecutor 2~4 pool = 분당 수십 건 이하. 초과 시 @Retryable 자동 재시도 |
| 무료 모델 품질 미달(confidence < 0.6 다수) | P2 | `LLM_MODEL` 교체만으로 코드 무수정 전환. 배포 후 10건 수동 채점 필수 |
| PromptGuard L2 통과 후 투자 권유 표현 | P1 | PromptGuard 9패턴 가드 유지 — OpenRouter 모델 교체와 무관하게 동일 적용됨 (CLAUDE.md §7) |

### 예상 wave 수

- **1 wave** — 4개 카드 모두 `infrastructure/llm/`·`shared/util/` 집중, 도메인 간 영향 없음. 단일 커밋으로 충분.
- 구현 후 로컬 smoke test (mock → openrouter 전환, 공시 1건 수동 확인) 권장.
- `.env.prod` 업데이트는 배포 인프라 Spec(`deployment-infra-docker-cicd`)과 연계해 처리.
