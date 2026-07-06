---
type: spec
status: Approved
created: 2026-07-06
updated: 2026-07-06
---

# analysis Stage 5 — 재무/업황 분석 (Premium) Spec

> 상태: Draft → **Approved** (2026-07-06, dc-tech-review 승인 + 결정 4건 확정)
> 마일스톤: Phase 1 최종 단계 — **Premium 티어 가치 완성** (Free 1~2 / Pro 1~4 / **Premium 1~5**)
> 선행 의존: [[analysis-stage4-llm-final]] (Approved, 2026-07-06 구현 완료 — 커밋 2750364)

## 배경 / 목적

### 문제 정의

분석 파이프라인의 마지막 단계. 통합기획서 §6.1 Stage 5 정의:

- **입력**: Stage 4 결과 + 회사 코드
- **처리**: ① 분기 재무 데이터 조회(DART 정기보고서) → ② 업황 데이터 조회(공공 API) → ③ LLM 프롬프트 구성 → ④ LLM 호출
- **출력**: 재무 영향 + 업황 맥락 + 리스크 평가 (입력 ~3,000 / 출력 ~500 토큰)

현재 상태: `AnalysisResponse.financial_context`는 **모든 티어에서 항상 null**(api_spec §2.4 미구현 정책 명시). FE 상세 페이지는 Premium 게이트 + raw JSON 출력 자리(`disclosures/[id]/page.tsx:278`)까지 준비돼 있으나 데이터 소스가 없다. 재무 데이터 수집 인프라(DART `fnlttSinglAcnt.json` 클라이언트·테이블·배치)도 전무하다.

### 페르소나 / BM 티어

- 페르소나 D(다종목 장기 투자)·B(적극적 정보 수집) — "이 공시가 회사 체력(재무) 관점에서 어떤 의미인가"
- **PREMIUM 전용** (통합기획서 §3.3). 응답 화이트리스트 게이팅 지점은 `AnalysisResponse.from()`의 `TODO Stage-5` 주석(교체 지점 명시됨).

### 범위 결정 (조사 근거)

**업황(공공 API)은 본 Spec에서 후속 확장으로 분리한다.** 근거: api_spec §3.3 — *"공공데이터포털 산업/업황 통계. 구체 서비스는 미확정(후보 단계)"*. API 선정 리서치가 선행돼야 하므로, Stage 5 MVP는 **재무 분석만으로 가동**하고 출력 스키마에 `industry_context`를 nullable로 예약한다. (Stock 엔티티에 `sector` 필드가 이미 있어 업황 매칭 키는 준비됨.)

## 요구사항

### Wave 1 — 재무 데이터 수집 인프라

- [ ] **R1. DartFinancialClient** — `infrastructure/dart/`에 `fnlttSinglAcnt.json`(단일회사 주요계정) 호출 클라이언트. 기존 `DartClient` 패턴(RestClient·타임아웃·@Retryable·status 코드 분기 000/013/020) 답습. 파라미터: `corp_code`, `bsns_year`, `reprt_code`(11013 1Q / 11012 반기 / 11014 3Q / 11011 사업보고서 — **DART 명세 실측 검증 필요**).
- [ ] **R2. financial_snapshots 테이블 (Flyway V{n})** — 회사×분기 단위 주요계정 스냅샷 저장. 최소 컬럼: `corp_code`, `bsns_year`, `reprt_code`, 매출액·영업이익·당기순이익·자산총계·부채총계·자본총계 (전기 대비 산출 가능하게 당기·전기 쌍), `fetched_at`. `(corp_code, bsns_year, reprt_code)` UNIQUE 멱등. `stock_prices` 보조 테이블 선례(db_schema §4 주석) 답습.
- [ ] **R3. 분기 배치 + 시드 백필** — `FinancialSyncJob`(분기 1회 스케줄, api_spec §3.1 "분기 배치") + 관리자 시드 백필 API(341종목 × 최근 4~8분기, 커서 재개 — `Stage4BackfillService` 패턴). DART 일 20k 쿼터 내(341종목 × 8분기 = ~2,728콜, [[reference_dart_api_quota]] 참조) throttle 적용.

### Wave 2 — Stage 5 분석기

- [ ] **R4. Stage5Output record** — `financialImpact`(String) + `industryContext`(String, nullable — 업황 후속) + `riskAssessment`(String) + `confidence`. 스키마 강제 파싱(CLAUDE.md §6-6).
- [ ] **R5. LlmClient.classifyStage5** — 인터페이스 + Ollama/OpenRouter/Mock 3종 구현(Stage 4 패턴 답습).
- [ ] **R6. Stage5PromptBuilder** — Stage 2/4 결과 + 재무 스냅샷(당기·전기 대비 증감, 룰 기반 산출 수치 그대로 주입) 조립. "제공된 수치만 사용" 강제(환각 방지). 자본시장법 가드 L1 명시.
- [ ] **R7. Stage5Analyzer** — skip 게이트: ① Premium 분석 대상 아님(하단 R10 예산 전략) ② withheld ③ stage_reached < 4(Stage 4 미완) ④ **재무 스냅샷 없음**. 통과 시 LLM 호출 → PromptGuard(재무 텍스트도 권유 표현 가드) → **`stage_details` JSONB에 stage5 키 병합 + stage_reached=5** (db_schema §5 SSOT: *"analysis_results.stage_details 보강"* — 신규 컬럼 없음, `AnalysisResult` UPDATE만).
- [ ] **R8. 응답 조립** — `AnalysisQueryService`에서 stage_details의 stage5 데이터를 `financial_context`로 역직렬화, `AnalysisResponse.from()`의 `TODO Stage-5` 지점에서 **PREMIUM만** 노출.
- [ ] **R9. 파이프라인 연결 + 백필** — `AnalysisOrchestrator` Stage 4 후 트리거(실패 격리) + Stage 5 백필 잡(stage_reached=4 대상, Stage4Backfill 패턴).

### 비기능 요구사항

- [ ] **R10. LLM 예산 가드** — Stage 5 추가 시 공시당 최대 3회 LLM 호출(Stage 2+4+5). OpenRouter `:free` 일 1,000건 소진 가속. **결정 필요**: (A) 전건 (B) 재무 관련 공시 유형만(실적·계약·증자 등 화이트리스트) (C) 재무 스냅샷 존재 시 전건. Stage 4의 (C) 방식 선례.
- [ ] **R11. AnalysisStage 상수화** — Stage 4 리뷰 M-2 이월: `AnalysisStage.RULE=1 … FINANCIAL=5` 상수 클래스 도입 + 기존 리터럴(2·4) 일괄 치환. 본 Spec에서 함께 처리(약속된 타이밍).
- [ ] **R12. 테스트** — DartFinancialClient 단위(응답 파싱·status 분기) + FinancialSyncJob/백필 Testcontainers IT + Stage5Analyzer skip 게이트 단위 + AnalysisResponseTest PREMIUM 분기 갱신(현재 `financialContext null` 검증을 non-null 케이스로 교체 — 테스트 주석에 예고됨). Mock DB 금지.

### 후속 분리 (본 Spec 범위 외)

- **업황 공공 API 결합** — data.go.kr 서비스 선정 리서치 후 별도 Spec (`industry_context` 필드 채움 + 월 1회 배치).
- **FE 구조화 UI** — raw JSON → 재무 카드(증감 화살표·리스크 배지). 별도 FE Spec 권장 (`disclosures/[id]/page.tsx:14` 주석에 예고됨). 접근성: 증감을 색상 단독 표기 금지(§6-5).

## 영향 범위 (조사 결과)

### 신규
- `backend/.../infrastructure/dart/DartFinancialClient.java` (+ dto)
- `backend/.../stocks/` 또는 `analysis/` — `FinancialSnapshot` 엔티티 + repository + `FinancialSyncJob` (**도메인 배치 결정 필요**: 재무는 회사 단위 마스터성 데이터 → stocks 후보 / 분석 전용 소비 → analysis 후보. Tech Review에서 확정)
- `backend/src/main/resources/db/migration/V{n}__create_financial_snapshots.sql`
- `backend/.../analysis/dto/Stage5Output.java` · `services/Stage5Analyzer.java` · `services/Stage5PromptBuilder.java` · Stage5 백필 서비스/컨트롤러
- `backend/.../shared/enums/AnalysisStage.java` (R11 상수)

### 수정
- `infrastructure/llm/LlmClient.java` + 구현 3종 — `classifyStage5`
- `analysis/services/AnalysisOrchestrator.java` — Stage 5 트리거
- `analysis/services/AnalysisQueryService.java` — financial_context 조립(PREMIUM)
- `analysis/dto/AnalysisResponse.java` — `TODO Stage-5` 교체
- `analysis/entities/AnalysisResult.java` — `applyStage5(...)` (stage_details 병합)
- Stage 2/4 관련 파일 — AnalysisStage 상수 치환(R11)
- `.env.example` / `application.yml` — 배치 스케줄·throttle 설정

### DB 변경
- **Flyway 필요**: `financial_snapshots` 신규 테이블 (analysis_results는 무변경 — stage_details JSONB 재사용)

### 외부 계약
- **DART**: `fnlttSinglAcnt.json` 신규 소비 — api_spec §3.1에 이미 명세됨(분기 배치). 응답 구조는 **구현 전 실측 검증 필수**(환각 방지 — 계정과목명 `account_nm`, 당기금액 `thstrm_amount` 등 필드명 문서 대조).
- 공공 API: 본 Spec 범위 외(후속).
- LLM: 프롬프트 1종 추가(모델 계약 불변).

## 관련 패턴 / 과거 사례

- **외부 API 클라이언트**: `DartClient`(status 분기·@Retryable) — DartFinancialClient 직접 템플릿.
- **분기 배치 + 마스터 동기화**: `StockMasterSyncJob`(KRX 분기 1회) — FinancialSyncJob 선례.
- **Stage 분석기 + skip 게이트 + UPDATE**: `Stage4Analyzer`(방금 완료) — Stage5Analyzer 직접 템플릿.
- **백필 커서·조기 중단**: `Stage4BackfillService`(연속 예외 30건·멱등 재개) — 동일 답습.
- **stage_details JSONB 직렬화/역직렬화**: `Stage2Analyzer.serializeDetail` + `AnalysisQueryService.parseDetail` — stage5 키 병합 시 기존 Stage2Detail과 공존 구조 설계 필요(**주의**: 현재 stage_details에 Stage2Detail이 평면 저장됨 — stage5 병합 시 하위 호환 파싱 필수).
- **DART 쿼터**: [[reference_dart_api_quota]] — 키당 일 20k, throttle=0 버스트 시 IP 차단. 시드 백필 throttle 필수.
- (docs/solutions 디렉터리 없음 — learnings 검색 생략)

## 리스크 / 법적 검토

- **[P0 자본시장법 §11.1]** "리스크 평가" 텍스트가 매수/매도 판단 유도로 흐를 위험 — Stage 5 출력 3필드(financial_impact/industry_context/risk_assessment) 전부 PromptGuard 가드 + L1 프롬프트 금지 명시 + 면책 문구 동반(기존 L4). "재무 악화" 서술은 사실 기반 정보 제공으로 허용, "매도 권고"는 금지 — 경계 표현 프롬프트 예시 필수.
- **[P0 환각 — 재무 수치]** LLM이 재무 수치를 변형·생성하면 투자자 피해 직결. 수치는 룰 기반(당기·전기·증감률 서버 계산)으로 산출해 **프롬프트에 주입만** 하고, 응답의 수치 재인용을 최소화하는 출력 스키마 설계(서술은 방향성 중심, 수치는 FE가 스냅샷 원본에서 직접 렌더).
- **[P1 LLM 예산]** 공시당 최대 3회 호출. R10 결정 전 구현 진입 금지.
- **[P1 DART 쿼터]** 시드 백필 ~2,7k+콜 — 일 20k 내 여유 있으나 폴링·본문 수집과 공유. throttle(기존 `CONTENT_BACKFILL_THROTTLE_MS` 패턴) 적용.
- **[P2 재무 데이터 공백]** 신규 상장·분기 보고서 미제출 기업은 스냅샷 없음 → Stage 5 skip(게이트 ④)이 자연 처리. 금융업(은행·보험)은 계정과목 체계가 달라 `fnlttSinglAcnt` 응답 상이 가능 — **실측 확인 필요**.

## 권장 구현 방향

**3-Wave 분할 + 재무 우선, 업황 후속**:

1. **Wave 1 (수집)**: DartFinancialClient → V{n} 테이블 → FinancialSyncJob + 시드 백필. *배치를 먼저 돌려놓으면 Wave 2 개발 중 데이터가 쌓임(리드타임 흡수).*
2. **Wave 2 (분석)**: AnalysisStage 상수화(R11) → Stage5Output/classifyStage5/PromptBuilder/Analyzer → stage_details 병합 + 응답 조립 → 파이프라인 연결.
3. **Wave 3 (소급 + 검증)**: Stage 5 백필(stage=4 대상) + 테스트 일괄 + PREMIUM 실데이터 확인.

**트레이드오프 메모**:
- stage_details JSONB 병합(권장, db_schema §5 SSOT·Flyway 불필요) vs analysis_results 신규 컬럼(스키마 명시적이나 마이그레이션 + SSOT 개정 필요) → **전자 권장**, 단 기존 Stage2Detail 평면 구조와의 하위 호환 파싱이 Tech Review 핵심 검토점.
- FinancialSnapshot 도메인: stocks(마스터 데이터 예외 — 타 도메인 read-only 직접 의존 가능, CLAUDE.md §3-2) vs analysis(소비처 단일) → **stocks 권장**(재무는 공시 분석 외 확장 여지 있는 회사 기준 데이터).

**미해결 (Tech Review에서 확정)**:
1. R10 예산 전략 (A/B/C) — 사용자 결정 필요.
2. `fnlttSinglAcnt.json` 응답 필드 실측 검증 (구현 전 1회 실 호출).
3. FinancialSnapshot 도메인 배치 (stocks vs analysis).
4. 시드 백필 범위 (최근 4분기 vs 8분기 — LLM 프롬프트에 필요한 비교 깊이).

## Tech Review (dc-tech-review · 2026-07-06)

### 아키텍처 분해

- **영향 레이어**: backend(infrastructure/dart, stocks, analysis, shared) + DB(Flyway) + FE(후속 분리). 3-Wave.
- **재사용 자산 (조사 확정)**:
  - `DartClient`(status 000/013/020 분기·@Retryable·RestClient) → `DartFinancialClient` 직접 템플릿.
  - `StockMasterSyncJob`(`@Scheduled(cron="0 0 4 1 1,4,7,10 *")` 분기 1회) → `FinancialSyncJob` 선례.
  - `Stage4Analyzer`(skip 게이트·UPDATE) + `Stage4BackfillService`(연속 예외 30건·멱등 재개) → Stage5 직접 답습.
  - `Stock.corpCode`(UNIQUE)·`Stock.sector` 존재 → 재무 조회 키·업황 매칭 키 준비됨.
  - FE `disclosures/[id]/page.tsx:278` Premium 게이트 + raw JSON 자리 존재 → BE만 채우면 표시.
- **핵심 검토점 (조사로 확정된 위험)**: `AnalysisQueryService.parseDetail()`(line 72)가 stage_details를 **`Stage2Detail`로 직접 역직렬화**한다. Stage 5를 같은 JSONB에 병합하면 이 파서가 깨진다. 다행히 `Stage2Detail` 주석(line 16)에 *"Stage 4~5 병합 시 래퍼 도입"* 확장 지점이 예고돼 있음 → **`StageDetailEnvelope` 래퍼 record 도입**(stage2/stage5 중첩)이 정답. 하위 호환: 기존 평면 Stage2Detail JSON을 래퍼로도 읽을 수 있게 관대한 파싱(try 래퍼 → catch 평면 폴백).
- **신규**: `DartFinancialClient`+dto · `FinancialSnapshot` 엔티티/repo · `FinancialSyncJob` · `FinancialBackfill` 서비스/컨트롤러 · `Stage5Output`/`Stage5Analyzer`/`Stage5PromptBuilder` · Stage5 백필 · `AnalysisStage` 상수 · `StageDetailEnvelope` 래퍼 · V31 마이그레이션.
- **수정**: `LlmClient`+구현3종 · `AnalysisOrchestrator` · `AnalysisQueryService`(parseDetail 래퍼화 + financial_context 조립) · `AnalysisResponse`(TODO Stage-5 교체) · `AnalysisResult`(applyStage5) · Stage2/4 리터럴→상수(R11).

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `fnlttSinglAcnt.json` 실 호출 1회 — 응답 필드(account_nm/thstrm_amount 등) 실측 검증 | infra(운영) | BE | 하 | - |
| 2 | `DartFinancialClient` + dto (status 분기·@Retryable, DartClient 답습) | backend/infra(dart) | BE | 중 | #1 |
| 3 | V31 `financial_snapshots` + `FinancialSnapshot` 엔티티/repository | backend/stocks + db | BE | 중 | #1 |
| 4 | `FinancialSyncJob`(분기 cron) + 시드 백필 서비스/컨트롤러(커서·throttle) | backend/stocks | BE | 중 | #2,#3 |
| 5 | `AnalysisStage` 상수 클래스 + 기존 리터럴(2·4) 일괄 치환 (R11, Stage4 이월) | backend/shared+analysis | BE | 하 | - |
| 6 | `StageDetailEnvelope` 래퍼 + `parseDetail` 하위호환 파싱 전환 | backend/analysis | BE | 상 | - |
| 7 | `Stage5Output` + `LlmClient.classifyStage5` + 구현 3종 파서 | backend/infra(llm)+analysis | BE | 중 | #5 |
| 8 | `Stage5PromptBuilder`(재무 증감 룰 산출 주입, 수치 변형 금지) | backend/analysis | BE | 중 | #7 |
| 9 | `Stage5Analyzer`(skip 게이트 4종 + LLM + PromptGuard + stage_details 병합 UPDATE) | backend/analysis | BE | 상 | #6,#7,#8 |
| 10 | `AnalysisOrchestrator` Stage5 트리거 + `AnalysisQueryService` financial_context 조립(PREMIUM) + `AnalysisResponse` TODO 교체 | backend/analysis | BE | 중 | #9 |
| 11 | Stage5 백필 서비스/컨트롤러 (stage=4 대상, Stage4Backfill 답습) | backend/analysis | BE | 중 | #9 |
| 12 | 테스트 — DartFinancialClient 단위 + FinancialSyncJob IT + Stage5Analyzer 게이트 + AnalysisResponseTest PREMIUM 갱신 | backend | BE | 중 | #4,#9,#11 |

### DB / 마이그레이션 영향

- **Flyway 필요**: `V31__create_financial_snapshots.sql` (최신 V30 다음). 컬럼: `id`·`corp_code`(FK stocks 아님 — 값 참조)·`bsns_year`·`reprt_code`·매출액/영업이익/당기순이익/자산총계/부채총계/자본총계(당기·전기 쌍, NUMERIC)·`fetched_at`. `UNIQUE(corp_code, bsns_year, reprt_code)` 멱등. 인덱스 `idx_fin_corp(corp_code)`.
- **analysis_results 무변경** — stage_details JSONB 재사용(db_schema §5 SSOT). 단 파서 구조는 카드 #6에서 변경.
- db_schema.md §4에 financial_snapshots 표 추가 필요(문서 동기 — dc-doc-sync).

### 외부 계약 영향

- **DART `fnlttSinglAcnt.json`**: api_spec §3.1 명세됨. **응답 구조 실측 미검증 → 카드 #1이 게이트**(환각 방지 원칙 — 문서만으로 파서 작성 금지). 금융업(은행·보험) 계정체계 상이 가능성 확인 포함.
- LLM: classifyStage5 프롬프트 1종 추가. 모델 계약 불변.
- 공공 API(업황): 본 Spec 범위 외(후속 Spec).

### 리스크 & 법적 검토

- **[P0 환각 — 재무 수치]** 수치는 서버가 룰 기반 산출(당기·전기·증감률) → 프롬프트 주입만. LLM 출력 스키마는 방향성 서술 중심, 수치 원본은 FE가 스냅샷에서 직접 렌더(카드 #8 프롬프트 설계 + 후속 FE Spec).
- **[P0 자본시장법 §11.1]** risk_assessment 3필드 전부 PromptGuard(`isRationaleViolation` 재사용 가능) + L1 프롬프트 금지 + L4 면책. "재무 악화"(사실) 허용 / "매도 권고" 금지 경계 프롬프트 예시.
- **[P1 stage_details 하위호환]** 카드 #6이 실패하면 기존 Stage2 상세(key_points/요인)가 전 사용자에게 사라짐 → **회귀 위험 최상**. 폴백 파싱 + 기존 데이터 대상 파서 테스트 필수.
- **[P1 DART 쿼터]** [[reference_dart_api_quota]] 일 20k. 시드 백필 throttle(`CONTENT_BACKFILL_THROTTLE_MS` 패턴 재사용).

### 예상 wave 수

- **Wave 1 (수집)**: 카드 #1~#4 — 배치 먼저 가동해 Wave 2 개발 중 데이터 축적(리드타임 흡수).
- **Wave 2 (분석)**: 카드 #5~#10 — 상수화 → 래퍼 → Stage5 파이프라인.
- **Wave 3 (소급+검증)**: 카드 #11~#12.

### 확정 결정 (dc-tech-review 판단 — 기술 사안)

- **#2 응답 실측 검증** → **카드 #1로 게이트화 확정.** 구현(#2) 전 실 호출 1회 필수 — 문서만으로 파서 작성은 환각 원칙 위반. **에이전트가 진행 가능**(구현 단계에서 실 API 호출).
- **#3 FinancialSnapshot 도메인** → **stocks 확정.** 근거: 재무는 회사(corp_code) 기준 마스터성 데이터로 공시 분석 외 확장 여지 있음. stocks는 마스터 도메인 예외(타 도메인 read-only 직접 의존 가능, CLAUDE.md §3-2)라 analysis가 소비하기에 정합. StockMasterSyncJob과 배치 인프라도 공유.
- **stage_details 저장 방식** → **JSONB 병합 + StageDetailEnvelope 래퍼 확정.** db_schema §5 SSOT가 "stage_details 보강"으로 명시 + Flyway 회피 + Stage2Detail 주석의 예고된 확장 지점.

### 확정 결정 (2026-07-06, 사용자 승인)

- **#1 LLM 예산 = (C) 재무 스냅샷 존재 시 전건** — Stage5Analyzer skip 게이트 ④(재무 스냅샷 없음)가 예산 가드를 겸함. 코스피200+코스닥150은 대부분 스냅샷 보유 → 실질 전건에 가까우나, 신규상장·미제출은 자연 skip. Stage 4 (C) 방식과 일관. 폴링 실측 후 초과 위험 시 유료 전환/B 재검토.
- **#4 시드 백필 깊이 = 8분기(2년)** — 약 2,728콜(DART 일 20k의 14%). 전년 동기 대비(YoY) 비교 가능 → 계절성 업종(유통·건설 등) 분석 품질 확보. `FinancialSyncJob` 분기 배치가 이후 최신 분기를 자동 추가하므로 시드는 1회성.
- **#2 응답 실측** → 카드 #1 게이트(에이전트 구현 중 실 호출).
- **#3 도메인** → stocks 확정.
- **stage_details** → JSONB 병합 + StageDetailEnvelope 래퍼 확정.

