---
type: spec
status: Approved
created: 2026-07-03
updated: 2026-07-03
---

# Stage 2 프롬프트 본문(content_text) 투입 Spec

> 상태: Draft → **Approved** (2026-07-03, dc-tech-review 승인 + 결정 3건 확정)

## 배경 / 목적

`[[disclosure-detail-redesign]]` 목업의 **"이런 내용이에요"(단계별 해설)·호재/악재 요인**은 공시 *본문*의 구체 내용(예: CB 1,000억·전환가액·지분 희석)에서 나온다. 그러나 현재 `Stage2PromptBuilder`는 **제목·유형 메타데이터만** LLM에 전달한다(코드 명시: "본문 미사용, 메타 기반 분류"). 결과적으로 Wave 2에서 추가한 `key_points`/`positive_factors`/`negative_factors`가 **제목 에코 수준**으로 빈약하다.

**실측(2026-07-03)**: gemma3:4b로 "SK하이닉스 증권신고서(지분증권)", "삼성E&A 최대주주등소유주식변동신고서" 재분석 → key_points가 "회사명/제목/접수일" 나열, **호재·악재 요인 전부 빈 배열**. 제목이 아주 유명한 경우("전환사채권발행결정")만 그럴듯했음.

이 Spec은 `Disclosure.content_text`(Stage 1 수집, ~67k건 보유, 최대 `contentMaxChars`≈5만자)를 **Stage 2 프롬프트에 truncate 투입**해 실질적 해설·요인을 생성한다.

- 페르소나: A(바쁜 직장인)·E(입문자, 용어 풀이) — 제목만으론 모르는 "이 공시가 실제로 뭘 하는가"를 본문 기반으로 설명.
- BM 티어: Free(요인/해설은 Free — disclosure-detail-redesign 확정).

## 요구사항

- [ ] `Stage2PromptBuilder`에 본문 섹션 추가 — `content_text`를 **Stage 2 전용 상한(신규, 5만자보다 작게)** 으로 절삭해 삽입
- [ ] 본문 없는 공시(content_text null/blank)는 기존처럼 메타 기반 분석 폴백 (요인 빈약 감수)
- [ ] Stage 2 전용 본문 상한 설정값(`@ConfigurationProperties`) — 로컬 모델 컨텍스트·비용 균형
- [ ] 원본 인용 필드(회사명·수치·날짜)는 본문에서 나와도 LLM 변형 금지 유지(CLAUDE.md §4) — 스키마 파싱 + 신뢰도 가드 유지
- [ ] 재분석 전략 확정 — 기존 분석(본문 있는 ~19k건 + 미분석 ~49k건)을 본문 기반으로 재생성
- [ ] 재분석 후 목업 요인/해설이 실제로 채워지는지 실 공시로 검증

## 영향 범위 (조사 결과)

- 영향 레이어: **backend(analysis)** 단독. FE 무관(응답 필드 계약 불변 — 값 품질만 향상).
- 영향 파일:
  - 수정: `analysis/services/Stage2PromptBuilder.java`(본문 섹션 + 절삭), `infrastructure/llm/LlmProperties.java` 또는 신규 프롬프트 설정(본문 상한), `infrastructure/llm/{Ollama,OpenRouter}LlmClient.java`(입력 토큰 증가에 따른 timeout·num_ctx 검토)
  - 재사용: `Stage2Analyzer`·`Stage2Output`·`PromptGuard`(요인 금지 키워드 스캔) 변경 없음 — 프롬프트만 풍부해짐
  - 참고: `disclosure/services/DisclosureContentService.java`(content_text는 `contentMaxChars`로 이미 절삭 저장)
- **DB 변경**: **없음**. content_text·stage_details(JSONB) 기존 활용. Flyway 불필요.
- **외부 계약**: LLM 프롬프트 변경(입력 토큰 대폭 증가). DART/KRX 무관.

## 관련 패턴 / 과거 사례

- `Stage2PromptBuilder` 주석에 이미 예고: *"본문(content_text) 추출 후속 Spec에서는 본 빌더에 본문 섹션 추가."* — 본 Spec이 그 후속.
- `[[analysis-stage2-smoke]]` (2026-07-02): 메타 전용 모델 비교. 본문 투입 후 재검 필요(gemma 낙관편향·품질 재평가).
- Wave 2(`[[disclosure-detail-redesign]]`)가 key_points/factors 필드·FE 카드는 이미 완성 — **본 Spec은 그 값의 품질만 채움**(계약·UI 무변경).

## 리스크 / 법적 검토

- **토큰·비용 (핵심)**: 본문 5만자 ≈ 1.2~2.5만 토큰(한국어). MVP Ollama(qwen3/gemma 4b)는 **컨텍스트 한도**로 잘릴 수 있고, 실서비스 OpenRouter는 **입력 토큰당 과금** → 공시당 비용 급증. → Stage 2 전용 상한을 **보수적으로(예: 4천~8천자)** 두는 게 필수. DART 공시는 앞부분에 결정사항·핵심을 배치하므로 앞 N자만으로도 대부분 커버(확인 필요).
- **자본시장법 §11.1 + 환각**: 본문 투입은 LLM이 참조할 사실이 많아져 해설 품질↑이나, 동시에 **수치·계약상대방 오인용(환각) 표면 확대**. PromptGuard(금지 키워드) 유지 + 신뢰도 낮으면 판단 보류 + 원본 인용 필드 변형 금지 프롬프트 강화 필요.
- **재분석 규모·시간**: 본문 있는 ~19k(분석됨)+~49k(미분석) 재분석은 Ollama 직렬이면 수 시간. idFrom/idTo 범위 배치로 분할 실행. 실서비스는 비용까지.
- **모델 컨텍스트 초과**: 로컬 4b 모델이 긴 본문에서 품질 저하·무응답 가능 — 상한으로 방어 + 본문 투입 후 gemma/qwen 재평가(확인 필요).

## 권장 구현 방향

- **본문 앞 N자 절삭 투입(권장, 최소 변경)**: `Stage2PromptBuilder`에 "공시 본문(발췌)" 섹션 추가, `content_text` 앞 `stage2BodyMaxChars`(신규 설정, 예: 6000자)까지. DART 공시가 핵심을 앞에 배치하는 특성 활용. 프롬프트에 "발췌 본문 기반으로 key_points/요인 작성, 없는 사실 추측 금지" 지시.
  - 트레이드오프: 앞 N자 밖의 핵심을 놓칠 수 있음(예: 뒤에 나오는 조건). 단순·저비용. → 대안: 요약/섹션 추출(복잡·후속).
- **재분석**: 우선 **본문 있는 공시만** 대상(요인이 실제로 개선되는 집합). idFrom/idTo 배치로 분할. 검증용으로 소수 먼저 재분석 → 품질 확인 후 전량.
- **모델**: 본문 투입 시 gemma vs qwen 품질이 메타 전용과 달라질 수 있음 → [[analysis-stage2-smoke]] 재검으로 확정(현재 미결정 트랙과 병합).
- **설정 격리**: 본문 상한을 `LlmProperties` 또는 신규 `Stage2PromptProperties`에 두어 코드 무수정 튜닝.

### 확인 필요
- DART 공시 앞 N자만으로 핵심 커버되는 비율(본문 구조 실측)
- 로컬 4b 모델의 실질 컨텍스트 한도 + 6000자 투입 시 품질/지연
- 실서비스 OpenRouter 비용 추정(공시당 입력 토큰 × 월 공시량)
- 재분석 범위(본문 있는 것만 vs 전량) 및 실행 시점

## Tech Review (dc-tech-review · 2026-07-03)

### 확정된 결정 (사용자 승인 · 2026-07-03)
1. **본문 상한 = ~6,000자** — DART 공시 앞부분 핵심(결정사항) 배치 특성 활용, 토큰/비용 균형. `stage2BodyMaxChars=6000` 기본값. (구현 시 앞 N자 커버율 실측으로 미세조정)
2. **재분석 범위 = 최근 공시만(기간 한정)** — 전량(~68k) 대신 최근 기간의 본문 보유 공시만. 사용자가 실제 보는 최신 공시를 우선 목업화. 과거 공시는 기존 질(제목 기반) 유지. 실행은 `idFrom/idTo`(또는 rcept_dt 기반 id 범위) 배치로 구체 범위 지정.
3. **모델 결정 = 구현 중 smoke로 병행 확정** — 본문 투입 코드 완성 후 gemma vs qwen을 **본문 포함** smoke test로 요인/분류 품질 실측 비교 → 확정. 메타 전용 결론(qwen)이 본문 후 바뀔 수 있음. [[analysis-stage2-smoke]] 미결정 트랙을 이 시점에 종료.

### 아키텍처 분해
- 영향 레이어: **backend(analysis + infrastructure/llm)** 단독. FE·DB·외부 계약 무변경.
- 신규: `LlmProperties.stage2BodyMaxChars`(설정 필드), Stage2PromptBuilder 본문 섹션.
- 수정: `Stage2PromptBuilder.build`(본문 발췌 삽입), `OllamaLlmClient`(num_ctx 상향), `OpenRouterLlmClient`(timeout/비용 주석), 재분석 운영 실행.

### 🔴 결정적 발견 (조사 기반)
- **`OllamaLlmClient`에 `num_ctx` 미설정** — 현재 options에 `temperature`·`num_predict`(800)만 있고 `num_ctx` 없음 → Ollama **기본 컨텍스트(모델별 ~2048 토큰)** 적용. 이 상태로 본문 6000자(~2~3천 토큰)를 프롬프트에 넣으면 **모델이 초과분을 조용히 절단** → 본문 투입 효과 소멸. **`num_ctx`를 프롬프트+본문 수용 크기(예: 8192)로 명시 상향해야 본 Spec이 실제로 동작함.** (본 Spec의 진짜 핵심 카드)
- `content-max-chars: 50000`(CONTENT_MAX_CHARS) — content_text는 Stage 1에서 이미 5만자 절삭. Stage 2는 이보다 **더 작은 별도 상한**(stage2BodyMaxChars) 필요.

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `LlmProperties.stage2BodyMaxChars` 설정 추가(@DefaultValue **6000** 확정) + application.yml | backend/infra | BE | 하 | - |
| 2 | `Stage2PromptBuilder`에 "공시 본문(발췌)" 섹션 추가 — content_text 앞 stage2BodyMaxChars 절삭(서로게이트 경계 보호), null/blank 폴백 유지 | backend/analysis | BE | 중 | 1 |
| 3 | **`OllamaLlmClient` num_ctx 명시 상향**(본문+프롬프트 수용, 예 8192) — 없으면 본문 절단 | backend/infra | BE(LLM) | 중 | 2 |
| 4 | `OpenRouterLlmClient` 입력 토큰 증가 대응 — timeout 여유 + 비용 주석(공시당 입력 토큰↑) | backend/infra | BE | 하 | 2 |
| 5 | Stage2PromptBuilder 단위 테스트 — 본문 섹션 포함/절삭/null 폴백 검증 | backend/analysis | BE | 하 | 2 |
| 6 | 재분석 실행 — 본문 포함 smoke(gemma vs qwen 확정) → 소수 재분석 육안 검증 → **최근 기간 한정** idFrom/idTo 배치 재분석(전량 아님) | 운영/BE | BE | 중 | 3,4 |

### DB / 마이그레이션 영향
- **Flyway 불필요** — content_text·stage_details(JSONB) 기존 컬럼 활용, 스키마 무변경.

### 외부 계약 영향
- **LLM 프롬프트만 변경**(입력 토큰 대폭 증가). 응답 스키마(Stage2Output/AnalysisResponse) 불변 → FE·api_spec 무변경.
- Ollama `num_ctx` 상향은 **메모리·지연 증가**(로컬 4b 모델) — 실측 확인 필요(확인 필요 항목).
- OpenRouter는 입력 토큰당 과금 → 공시당 비용 급증(월 공시량 × 본문 토큰).

### 리스크 & 법적 검토
- **[핵심] num_ctx 미상향 시 무효**: 카드 #3 누락하면 본문 넣어도 절단되어 아무 효과 없음 — 구현 시 반드시 실측(본문 반영 여부 로그/응답 확인).
- **자본시장법 §11.1 + 환각 확대**: 본문 투입으로 LLM 참조 사실↑ → 해설 품질↑이나 수치·계약상대방 오인용 표면도↑. 원본 인용 필드(회사명·수치·날짜) 변형 금지 프롬프트 강화 + PromptGuard(금지 키워드) 유지 + 신뢰도 낮으면 판단 보류.
- **비용/시간**: 재분석 대규모(본문 있는 ~19k 분석됨 + ~49k 미분석). 로컬 Ollama 직렬 수 시간, OpenRouter 비용. → 소수 검증 후 배치 분할(카드 #6).
- **모델 재평가**: 본문 투입 후 gemma vs qwen 품질이 메타 전용과 달라짐 → [[analysis-stage2-smoke]] 재검(미결정 모델 트랙과 병합).

### 예상 wave 수
- **1 wave(코드) + 운영 재분석**. 코드(카드 1~5)는 단일 PR. 재분석(카드 6)은 코드 배포 후 소수 검증→배치 실행하는 운영 단계.

<!-- 다음: 확인 필요(앞 N자 커버율·로컬 모델 컨텍스트 한도·OpenRouter 비용·재분석 범위) 판단 → /dc-spec-move Approved → /dc-implement -->
