---
type: spec
status: Approved
created: 2026-06-08
updated: 2026-06-08
---

# sentiment-to-shared Spec

> 상태: Draft → **Approved** (2026-06-08, dc-tech-review 승인)

## 배경 / 목적

- **문제**: `Sentiment` enum이 `com.dartcommons.analysis.entities.AnalysisResult`의 중첩 enum으로 정의되어 있어,
  `notification/`, `infrastructure/`, `shared/event/` 패키지가 `analysis` 도메인에 직접 의존.
  CLAUDE.md §3-2 "도메인 간 직접 의존 금지" 위반. MVP 한시 허용으로 기록했으나 기술 부채로 누적.
- **해결**: `Sentiment`를 `com.dartcommons.shared.enums.Sentiment` 독립 enum으로 이관.
  import 방향: `shared → domain` (역방향 금지) 원칙에 맞게 정합 복구.
- **대상**: 사용자·비즈니스 가치 없음 — 순수 아키텍처 정합성 리팩토링.
- **BM 티어**: 해당 없음 (인프라 레이어)

## 요구사항

- [ ] `com.dartcommons.shared.enums.Sentiment` 생성: `POSITIVE`, `NEUTRAL`, `NEGATIVE`
- [ ] `AnalysisResult` 내 중첩 enum `Sentiment` 제거 → `shared.enums.Sentiment` 참조로 교체
- [ ] `AnalysisResult.sentiment` 필드 타입을 `shared.enums.Sentiment`로 변경
- [ ] 하기 파일 import 경로 일괄 수정:
  - `analysis/entities/AnalysisResult.java` — 중첩 enum 제거, 필드 타입 변경
  - `analysis/dto/Stage2Output.java`
  - `analysis/dto/AnalysisResponse.java`
  - `notification/services/NotificationDispatcher.java`
  - `notification/services/NotificationMessageBuilder.java`
  - `infrastructure/llm/OllamaLlmClient.java`
  - `infrastructure/llm/MockLlmClient.java`
  - `shared/event/AnalysisCompletedEvent.java`
  - 테스트 5개 파일 (하기 영향 범위 참조)
- [ ] DB 마이그레이션 불필요 — `analysis_results.sentiment` 컬럼은 VARCHAR 값(`POSITIVE`/`NEUTRAL`/`NEGATIVE`) 그대로 유지
- [ ] JPA `@Enumerated(EnumType.STRING)` 선언이 `AnalysisResult.sentiment`에 존재하면 타입 변경 후 동작 확인
- [ ] 전체 테스트 통과 확인 (`./gradlew test`)

## 영향 범위

- **영향 레이어**: backend(shared, analysis, notification, infrastructure)
- **신규 파일**:
  - `backend/.../shared/enums/Sentiment.java` — enum POSITIVE / NEUTRAL / NEGATIVE
- **수정 파일 (본문)**:
  - `backend/.../analysis/entities/AnalysisResult.java` — 중첩 enum 제거, 필드 타입
  - `backend/.../analysis/dto/Stage2Output.java`
  - `backend/.../analysis/dto/AnalysisResponse.java`
  - `backend/.../notification/services/NotificationDispatcher.java`
  - `backend/.../notification/services/NotificationMessageBuilder.java`
  - `backend/.../infrastructure/llm/OllamaLlmClient.java`
  - `backend/.../infrastructure/llm/MockLlmClient.java`
  - `backend/.../shared/event/AnalysisCompletedEvent.java`
- **수정 파일 (테스트)**:
  - `backend/src/test/.../analysis/Stage2AnalyzerIntegrationTest.java`
  - `backend/src/test/.../analysis/AnalysisOrchestratorIntegrationTest.java`
  - `backend/src/test/.../analysis/PromptGuardTest.java`
  - `backend/src/test/.../analysis/AnalysisWave1IntegrationTest.java`
  - `backend/src/test/.../notification/NotificationDispatcherIntegrationTest.java`
- **DB 변경**: 없음 (VARCHAR 값 동일)
- **외부 계약 변경**: 없음 (REST API 응답의 sentiment 문자열 값 불변)

## 관련 패턴 / 과거 사례

- CLAUDE.md §3-2: "도메인 간 직접 의존 금지 — shared/ 또는 이벤트 경유. import 방향: shared → 도메인 (역방향 금지)"
- WORKLOG 2026-06-08 Wave 2 결정: "cross-domain 한시 허용 — Sentiment → shared 이관 + Disclosure 요약 → event payload로 해소 예정"
- 기존 `shared/enums/` 패키지 부재 → 신규 생성

## 리스크 / 법적 검토

- **컴파일 브레이킹**: `AnalysisResult.Sentiment` → `Sentiment`로 import 경로 변경. 누락된 파일은 컴파일 오류로 즉시 탐지 가능.
- **JPA EnumType**: `@Enumerated(EnumType.STRING)` 사용 시 클래스명이 아닌 `.name()` 문자열 저장 → 동일 enum 값명 유지 시 DB 호환.
- **LangChain4j LLM 파싱**: LLM 응답에서 Sentiment enum을 파싱하는 코드가 있다면 FQCN이 아닌 `.valueOf(String)` 방식이므로 이관 후에도 동작 유지.
- **법적 제약**: 없음 (순수 리팩토링)

## 권장 구현 방향

**단일 Wave 구현**: 영향 파일이 13개이나 모두 단순 import 교체. 로직 변경 없음.

1. `shared/enums/Sentiment.java` 생성
2. `AnalysisResult.java` 중첩 enum 제거 + 필드 타입 변경
3. 나머지 8개 본문 파일 import 교체 (Replace All 가능)
4. 테스트 5개 import 교체
5. `./gradlew test` — 전체 통과 확인

> `grep -r "AnalysisResult.Sentiment" backend/src`로 누락 파일 확인 후 작업.

## Tech Review (dc-tech-review · 2026-06-08)

### 아키텍처 분해

- **영향 레이어**: backend(shared, analysis, notification, infrastructure) — 단방향 import 정합 복구
- **신규 파일**: `shared/enums/Sentiment.java`
- **수정 파일 (본문 8개)**:
  - `analysis/entities/AnalysisResult.java` — 중첩 enum 제거, 필드 타입 → `shared.enums.Sentiment`
  - `analysis/dto/Stage2Output.java` — import 교체
  - `analysis/dto/AnalysisResponse.java` — import 교체
  - `notification/services/NotificationDispatcher.java` — import 교체
  - `notification/services/NotificationMessageBuilder.java` — import 교체
  - `infrastructure/llm/OllamaLlmClient.java` — import 교체
  - `infrastructure/llm/MockLlmClient.java` — import 교체
  - `shared/event/AnalysisCompletedEvent.java` — import 교체
- **수정 파일 (테스트 5개)**:
  - `analysis/Stage2AnalyzerIntegrationTest.java`
  - `analysis/AnalysisOrchestratorIntegrationTest.java`
  - `analysis/AnalysisWave1IntegrationTest.java`
  - `analysis/PromptGuardTest.java`
  - `notification/NotificationDispatcherIntegrationTest.java`

### 작업 카드

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `shared/enums/Sentiment.java` 생성 — `POSITIVE`, `NEUTRAL`, `NEGATIVE` | shared | 하 | - |
| 2 | `AnalysisResult.java` — 중첩 enum 제거, `sentiment` 필드 타입 → `shared.enums.Sentiment` | analysis/entities | 하 | #1 |
| 3 | 본문 6개 파일 import 교체 — `AnalysisResult.Sentiment` → `shared.enums.Sentiment` (AnalysisResponse, Stage2Output, NotificationDispatcher, NotificationMessageBuilder, OllamaLlmClient, MockLlmClient, AnalysisCompletedEvent) | analysis/notification/infra/shared | 하 | #2 |
| 4 | 테스트 5개 파일 import 교체 | test | 하 | #3 |
| 5 | `./gradlew test` — 79/79 통과 확인 | - | 하 | #4 |

### DB / 마이그레이션 영향

- **마이그레이션 불필요** — `analysis_results.sentiment` 컬럼은 `VARCHAR` 값(`POSITIVE`/`NEUTRAL`/`NEGATIVE`) 그대로. `@Enumerated(EnumType.STRING)`은 클래스명이 아닌 `.name()` 문자열 저장 → 이관 후 완전 호환.

### 외부 계약 영향

- **LLM 파싱 안전**: `OllamaLlmClient`의 `Sentiment.valueOf(raw.trim().toUpperCase())` 호출은 Java static valueOf 방식 → 클래스 경로 무관, 이관 후 동작 유지.
- **REST API 변경 없음**: `AnalysisResponse.sentiment` 직렬화는 enum `.name()` → 클라이언트에 노출되는 문자열 값 불변.
- **DART/KRX/카카오 무관**.

### 리스크 & 법적 검토

- **컴파일 브레이킹 즉시 탐지**: `AnalysisResult.Sentiment` 참조 파일 누락 시 컴파일 오류로 즉시 탐지. `grep -r "AnalysisResult.Sentiment"` 잔존 확인으로 완전 제거 검증.
- **법적 제약 없음** — 순수 리팩토링, 사용자 노출 변경 없음.

### 예상 wave 수

- **단일 Wave** — 전 카드가 단순 import 교체, 로직 변경 없음. 컴파일 통과 + 79/79 테스트 통과로 완료.
