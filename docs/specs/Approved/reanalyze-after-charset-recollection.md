---
type: spec
status: Approved
created: 2026-07-04
updated: 2026-07-04
---

# charset 재수집 후 손상본 기반 분석결과 재분석 Spec

> 상태: Draft → **Approved** (2026-07-04, dc-tech-review 승인 + 결정 4건 확정)

## 배경 / 목적

[[content-text-charset-mojibake]] 재수집으로 `content_text`의 mojibake는 0으로 정정됐다. 그러나 **그 손상 본문으로 이미 산출된 `analysis_results`(summary/key_points/호재·악재 요인/stage_details/sentiment/confidence)는 여전히 옛 손상 데이터 기반 값**으로 DB에 남아 있다. 콘텐츠 정합성은 회복됐지만 **분석 정합성은 미회복** 상태다.

**실측(2026-07-04)**: 94128 한전기술 계약 공시의 저장된 분석 summary가 여전히 `"총 계약 금액 448조 634억 원 규모"` — 재수집된 정상 본문(계약금액 33,177,947,138원·최근매출액 448,634,709,421원)과 **1000배 어긋난 환각**이 화면에 그대로 노출된다. `Stage2Analyzer`가 `existsByDisclosureId`로 재분석을 스킵하므로, 본문만 고쳐서는 분석이 갱신되지 않는다.

목적: 재수집으로 본문이 회복된 공시들의 **기존 분석을 무효화하고 정상 본문으로 재분석**해, 투자자에게 노출되는 분석의 수치·해설 정합성을 확보한다.

- 페르소나: A·E(개인 투자자) — 틀린 계약금액/매출 수치가 분석 카드에 노출되는 문제 해소.
- BM 티어: 무관(데이터 정합성 인프라). Stage 2 기준(Free 포함 전 티어 노출분).

## 요구사항

- [ ] 재분석 대상 식별 — 재수집 시점(2026-07-03~04) 이후 `content_fetched_at` + `analysis_results` 존재 공시 (**실측 7,284건**)
- [ ] 대상의 기존 `analysis_results` 무효화(삭제 또는 덮어쓰기) 후 `Stage2Analyzer`로 재분석 — 정상 본문 기반 재산출
- [ ] 배치/재개 가능(중단 복구), LLM 부하 격리(폴링 SLO 보호), 진행률 추적
- [ ] 94128 등 대표 케이스 재분석 후 수치 정합 육안 검증("448조원" 소멸 확인)
- [ ] (검토) `feedbacks` FK cascade로 사용자 신고 유실 방지 정책 확정
- [ ] (검토·링크) 동일 손상 본문으로 만든 **Stage 3 임베딩(Chroma)** 재생성 필요 여부 — [[stage3-embedding-backfill]] 연계

## 영향 범위 (조사 결과)

- 영향 레이어: **backend(analysis)**. 재분석은 기존 `analysis` 도메인 내부. FE·응답 계약 무변경(값만 정정).
- 영향 파일:
  - `analysis/services/AnalysisBackfillService.java` — 현재 `findUnanalyzedDisclosureIds`(NOT EXISTS)만 처리 → **분석 존재분은 스킵**. 재분석하려면 대상 분석 선삭제 or 강제 재분석 경로 필요.
  - `analysis/services/Stage2Analyzer.java:76` — `existsByDisclosureId` 가드로 재분석 스킵. 강제 재분석 모드 or 선삭제 전제.
  - `analysis/repositories/AnalysisResultRepository.java` — **delete 메서드 부재** → `deleteByDisclosureIdIn` 등 추가 필요(또는 운영 SQL).
  - `analysis/controllers/AnalysisBackfillController.java` — 재분석 트리거(id 범위 or reanalyze 플래그) 엔드포인트 확장 후보.
  - 참고(무변경): `Stage2PromptBuilder`(본문 투입 — [[stage2-body-in-prompt]]로 이미 content_text 반영).
- **DB 변경**: 스키마 불변(Flyway 불필요). `analysis_results` **데이터** 재산출. 단 삭제 방식 시 `feedbacks`(analysis_id ON DELETE CASCADE) 연쇄 삭제 주의 — **현재 feedbacks 0건이라 무해하나 프로드 대비 정책 필요**.
- **외부 계약**: 없음. 내부 LLM(gemma) 재호출만. DART/KRX/카카오 무관.
- **의존성**: Ollama(gemma3:4b) 가용성. LangChain4j. `analysisBackfillExecutor`(core1/max2) 풀.

## 관련 패턴 / 과거 사례

- **직접 후속**: [[content-text-charset-mojibake]](Done, 2026-07-04) — 본문 재수집 완결. 본 Spec은 그 "분석 정합" 미완료분을 잇는다.
- **[[stage2-body-in-prompt]]**(Done) — Stage2Analyzer가 content_text를 프롬프트에 투입. 재분석은 이 경로로 정상 본문을 읽어 올바른 수치 산출.
- **기존 백필 재사용**: `AnalysisBackfillService`(id 범위 + 청크 + 워터마크 + 안전망)를 그대로 활용 가능 — 단 "미분석만" 조회하므로 대상 선삭제가 전제.
- 운영 상수: [[reference_dart_api_quota]]은 DART용 — 본 작업은 LLM(Ollama 로컬)이라 API 할당량 무관, 대신 LLM 처리시간이 병목.

## 리스크 / 법적 검토

- **[핵심] 투자자 오도(자본시장법 §11 + CLAUDE.md §4)**: 틀린 계약금액/매출(448조원) 분석이 노출 중 — 원본 수치 정합성 직결. 본 Spec의 최우선 동기. **재분석 완료 전까지 리스크 지속**.
- **feedbacks cascade 유실**: 분석 삭제 시 `ON DELETE CASCADE`로 사용자 "부정확 신고" 동반 삭제. 현재 0건(무해)이나, 프로드에선 삭제 대신 **in-place 덮어쓰기** 또는 feedback 백업·재링크 필요(확인 필요).
- **재분석 중 노출 공백**: 선삭제→재분석 사이 구간엔 해당 공시 분석이 없음(화면 "분석 없음"). 청크 단위 삭제+재분석으로 공백 최소화 권장.
- **LLM 환각 재발**: 재분석도 gemma 사용 — 정상 본문이라도 요인 과생성/신뢰도 편차 가능. `PromptGuard` + confidence 보류 + 스키마 파싱으로 방어(기존 다층 방어 유지).
- **부분 실패/재개**: 7,284건 배치 중단 시 일부만 재분석된 혼재 상태 가능 — 잡 진행률·워터마크로 재개.
- **규모/시간**: 7,284건 × gemma LLM. 폴링 SLO 보호 위해 `analysisBackfillExecutor` 격리 유지, 야간 배치 권장.

## 권장 구현 방향

- **접근 A (권장, 기존 백필 재사용 + 선삭제)**: 대상 id의 `analysis_results` 삭제(운영 SQL 또는 신규 `deleteByDisclosureIdIn`) → `AnalysisBackfillService`를 해당 id 범위로 재실행(기존 `findUnanalyzedDisclosureIds`가 자동 포착). 코드 변경 최소. feedbacks 0인 현 시점에 적합. **노출 공백 최소화 위해 "대상 전체 일괄 삭제"보다 청크 단위 삭제+재분석 잡** 권장.
- **접근 B (재분석 전용 경로, 프로드 안전)**: `Stage2Analyzer`에 overwrite 모드(기존 결과 UPDATE, feedback FK 보존) + `AnalysisBackfillController`에 `reanalyze=true`/`since` 파라미터. 삭제 없이 정합 — feedback 보존. 코드 규모 큼.
- **범위 식별 쿼리(확정 근거)**:
  ```sql
  SELECT d.id FROM disclosures d JOIN analysis_results ar ON ar.disclosure_id = d.id
  WHERE d.content_fetched_at >= '2026-07-03';   -- 실측 7,284건
  ```
- **[[stage2-body-in-prompt]] 병합**: 그쪽 "전체 재분석 확대" 후속과 본 Spec은 동일 재분석 파이프라인 — 대상만 다름(본 Spec=재수집분, 그쪽=미투입 잔여). 한 잡으로 통합 가능.

### 확인 필요
- 접근 A(삭제) vs B(덮어쓰기) — MVP 속도(A) vs 프로드 feedback 보존(B). 현 feedbacks 0 → A로 시작, 프로드 전 B 승급?
- 재분석 범위: 재수집분 7,284만 vs [[stage2-body-in-prompt]] 미투입분까지 통합(더 큼)?
- Stage 3 임베딩 재생성 동반 여부 — 손상 본문으로 만든 Chroma 벡터도 오염. [[stage3-embedding-backfill]]과 순서/통합?
- 배치 타이밍(야간)·청크 크기(기존 100)·noload 시간대 정책.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-07-04)

### 확정 근거 (코드/DB 재확인)
- `Stage2Analyzer.analyze`(Stage2Analyzer.java:76): `existsByDisclosureId` → skip, 이후 **INSERT-only**(:122, `disclosure_id` UNIQUE). ⇒ 재분석은 **기존 결과 선삭제(A)** 또는 **skip 제거 + UPDATE 경로(B)** 중 택1이어야 성립.
- `AnalysisBackfillService.runAsync`: `findUnanalyzedDisclosureIds`(NOT EXISTS)만 조회 + 워터마크 커서 + 안전망(50건 시도 0성공 중단) + `analysisBackfillExecutor`(core1/max2, 폴링 격리). ⇒ **선삭제만 하면 기존 백필이 그대로 재분석 잡으로 재사용됨**.
- `AnalysisResultRepository`: delete 메서드 **부재** → 신규 필요.
- `feedbacks.analysis_id` → `analysis_results(id)` **ON DELETE CASCADE**(V9). 현재 feedbacks **0건**(실측) → A(삭제) 지금은 무손실. 프로드(feedback 존재) 시 B 필요.
- 대상 규모: `content_fetched_at >= '2026-07-03'` ∧ analysis 존재 = **7,284건**(실측). 순수 LLM(Ollama gemma) 작업 — DART 할당량 무관, LLM 처리시간이 병목.
- **스테일 잡 재개 갭(주의)**: `analysis_jobs`는 content-backfill과 달리 stale-RUNNING 자동 재개 로직 없음(2026-07-03 WORKLOG 관측). 중단 시 수동 재트리거 전제 — 재개 요구사항은 이 한계 내에서 설계.

### 아키텍처 분해
- 영향 레이어: **backend(analysis)** 전용. FE·응답 계약·DB 스키마 무변경(값 재산출).
- 신규: `AnalysisResultRepository.deleteByDisclosureIdIn`(또는 범위 delete), 재분석 트리거 메서드/엔드포인트(청크 삭제+재분석). (B 선택 시) `Stage2Analyzer` overwrite 모드.
- 수정: `AnalysisBackfillController`(재분석 파라미터) — 또는 운영 전용 경로로 최소화.
- 재사용(무변경): `AnalysisBackfillService`(잡·청크·워터마크), `Stage2PromptBuilder`(본문 투입), `PromptGuard`.

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `AnalysisResultRepository.deleteByDisclosureIdIn(ids)` + 재분석 대상 조회(`content_fetched_at >= :ts` ∧ 분석존재) 쿼리 | backend/analysis | BE | 하 | - |
| 2 | 재분석 잡 경로 — 대상을 **청크 단위로 선삭제→`Stage2Analyzer` 재분석**(노출 공백 최소화). `AnalysisBackfillService` 재사용 or 얇은 래퍼. 진행률·워터마크·격리 유지 | backend/analysis | BE(LLM) | 상 | #1 |
| 3 | 관리자 트리거 — `AnalysisBackfillController`에 재분석 엔드포인트(id 범위 or `since` 파라미터, `/admin` Basic). 중복 실행 방지 | backend/analysis | BE | 중 | #2 |
| 4 | (운영) 배치 실행 — 7,284건 대상 재분석 잡 트리거·모니터링·야간 실행. 94128 등 대표 케이스 수치 정합 육안 검증("448조원" 소멸) | 운영/BE | BE | 중 | #2,#3 |
| 5 | 회귀 테스트 — 선삭제→재분석 정상 산출(Testcontainers), 본문 반영 확인, 중단 후 재개(미분석분만 재처리) | backend/analysis | BE | 중 | #2 |
| 6 | (프로드 승급, 조건부) 접근 B — `Stage2Analyzer` overwrite 모드(기존 UPDATE, feedback FK 보존). feedbacks>0 되기 전 도입 | backend/analysis | BE | 상 | #2 |

### DB / 마이그레이션 영향
- **Flyway 불필요** — 스키마 무변경. `analysis_results` **데이터** 재산출.
- `feedbacks` ON DELETE CASCADE: A(삭제) 시 연쇄 — 현재 0건 무해. 카드 #6(B)로 프로드 대비.
- 인덱스 `idx_analysis_disclosure` 존재 → 대상 조회/삭제 효율 OK.

### 외부 계약 영향
- 없음. 내부 LLM(Ollama gemma) 재호출만. DART/KRX/카카오/FE 무관.
- **연계(범위 밖·링크)**: 손상 본문으로 만든 Stage 3 임베딩(Chroma)도 오염 가능 → [[stage3-embedding-backfill]]에서 별도 처리. 본 Spec은 Stage 2 재분석에 한정.

### 리스크 & 법적 검토
- **[P0] 투자자 오도(§11 + CLAUDE.md §4)**: 448조원 등 틀린 수치 노출 지속 — 재분석 완료가 해소 조건. 본 작업의 최우선 동기.
- **feedback 유실(A)**: 현재 0건 무해, 프로드는 카드 #6 필요(확인 필요).
- **노출 공백**: 청크 단위 삭제+재분석(카드 #2)로 "분석 없음" 구간 최소화.
- **LLM 환각 재발**: gemma 요인 과생성 — `PromptGuard`+confidence 보류+스키마 파싱 유지(기존 방어).
- **재개 한계**: analysis_jobs stale-RUNNING 자동재개 없음 → 중단 시 수동 재트리거(워터마크로 미분석분만).

### 예상 wave 수
- **1 wave(코드 카드 1~3,5) + 운영(카드 4)**. 코드 단일 PR(A 기준). 카드 #6(B)은 프로드 전 별도 wave(조건부).

### 확정된 결정 (사용자 승인 · 2026-07-04)
1. **접근 A(선삭제)로 시작** — 현 feedbacks 0으로 무손실. 카드 #6(B 덮어쓰기)은 **프로드(feedbacks>0) 전 조건부 후속**으로 분리(이번 구현 범위 밖).
2. **범위 = 재수집분 7,284건만 우선** — [[stage2-body-in-prompt]] 미투입 잔여 통합은 하지 않음(별도 후속). 대상: `content_fetched_at >= '2026-07-03'` ∧ analysis 존재.
3. **Stage 3 임베딩 재생성은 후행 분리** — 본 Spec은 Stage 2 재분석에 한정. Chroma 오염 정정은 [[stage3-embedding-backfill]]에서 별도 진행.
4. **배치: 야간 실행 + 청크 100** — `analysisBackfillExecutor`(폴링 격리) 유지, LLM 부하 시간대 분산.

⇒ 이번 구현 wave 범위: **코드 카드 1~3, 5 + 운영 카드 4**. 카드 #6은 제외(프로드 전 조건부).
