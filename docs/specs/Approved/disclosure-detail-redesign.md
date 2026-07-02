---
type: spec
status: Approved
created: 2026-07-02
updated: 2026-07-02
---

# 공시 상세 페이지 리디자인 Spec

> 상태: Draft → **Approved** (2026-07-02, dc-tech-review 승인)

## 배경 / 목적

현재 공시 상세 페이지(`/disclosures/[id]`)는 정보 밀도가 낮고 밋밋하다(스크린샷 1: 삼성E&A 공시 — 단일 요약 문단 + 신뢰도 바 + 잠긴 PRO/PREMIUM 카드만 노출). 목표 목업(스크린샷 2: CB 발행 상세)은 훨씬 풍부한 분석 화면을 요구한다:

- 상단 **AI 인덱스(%)** 우측 정렬 + 배지
- **한 줄 요약** 헤드라인
- **"이런 내용이에요"** — 단계별(numbered) 공시 해설
- **영향 요인** — 호재 요인 / 악재 요인 2컬럼
- **내 평균 매수가** 박스 — 보유 종목 손익선 대비 안내
- **매수영향 예측 차트** — D+1~D+5 막대 + 5일 평균 등락률
- **과거 유사 공시** 리스트(등락률) — PRO
- **재무·업황 보러가기** Premium 다크 CTA 카드
- **"이 분석이 도움이 되었나요?"** 유용해요/부정확해요 피드백

- 페르소나: A(바쁜 직장인 투자자)·E(입문 투자자, 용어 풀이 필요) — 한눈에 호재/악재 요인과 예상 반응을 파악.
- BM 티어: Free(판정+한 줄 요약+해설) / Pro(유사 공시+예측 차트+요인) / Premium(재무·업황). 목업의 티어 경계는 **Tech Review에서 확정 필요**.

## 요구사항

### FE-only (기존 데이터로 즉시 가능 — Wave 1 권장)
- [ ] 목업 시각 언어로 상세 페이지 레이아웃 재구성 (카드/2컬럼/여백/타이포)
- [ ] AI 인덱스: 기존 `confidence`(0~1)를 상단 우측 % + 배지로 렌더 (현 `ConfidenceMeter` 재활용/재배치)
- [ ] 한 줄 요약: 별도 필드 없음 → 우선 기존 `summary` 노출. 전용 헤드라인은 BE 의존(아래)
- [ ] 과거 유사 공시 리스트 + 등락률 (기존 `similar_disclosures` + `PriceReactionChart` 재활용)
- [ ] Premium 다크 CTA 카드 (기존 `TierGate` 스타일 교체)
- [ ] 피드백 프롬프트 (기존 `FeedbackPrompt` 재활용)
- [ ] `is_withheld`/`confidence<0.5` 판단 보류 처리 유지 (투자자 보호 — 회귀 금지)

### BE 신규 필드 의존 (Wave 2+ — 별도 카드로 분리)
- [ ] "이런 내용이에요" 단계별 해설 (`key_points: string[]` 등 신규 필드)
- [ ] 호재/악재 요인 2컬럼 (`positive_factors[]` / `negative_factors[]` 신규 필드)
- [ ] 한 줄 요약 전용 헤드라인 (`headline` 신규 필드, 없으면 summary 대체 유지)
- [ ] 매수영향 **예측** 차트 (현재는 과거 유사 반응만 존재 — forward D+1~D+5 예측값 데이터 필요)

### 포트폴리오 연동 (Wave 3 — 개인정보 경로)
- [ ] 내 평균 매수가 박스 — 보유 종목 평균 매수가(AES-256 암호화) × 현재가 조인

## 영향 범위 (조사 결과)

- 영향 레이어: **frontend(disclosures/[id])** 주도, BE는 Wave 2+에서 `analysis`
- 영향 파일:
  - `frontend/src/app/(app)/disclosures/[id]/page.tsx` — 리디자인 대상(현재 255줄, 단일 파일에 전 섹션)
  - `frontend/src/components/domain/PriceReactionChart.tsx` — 예측 차트 요구 시 확장
  - `frontend/src/components/domain/ConfidenceMeter.tsx` — AI 인덱스 % 표기 재배치
  - `frontend/src/components/domain/{FeedbackPrompt,SentimentBadge,DisclaimerNotice,TierGate}.tsx` — 재활용
  - `frontend/src/lib/api/disclosures.ts` — Wave 2+ 신규 필드 타입 추가 시
- BE(Wave 2+ 해당 시):
  - `backend/.../analysis/dto/AnalysisResponse.java` (record — 필드 추가)
  - `backend/.../analysis/entities/AnalysisResult.java` (신규 컬럼 or 기존 `stageDetails` String 활용)
  - `docs/개발명세서/api_spec.md` §2.4 동기 갱신
- **DB 변경**: Wave 1 없음. Wave 2에서 `key_points`/`factors`/`headline` 저장 방식에 따라 **Flyway 마이그레이션 필요 가능** — 단, 기존 `AnalysisResult.stageDetails`(String, 현재 용도 미확인) JSON 활용 시 스키마 무변경 가능성 있음(**확인 필요**)
- **외부 계약**: DART/KRX 불변. 신규 요인/해설/예측은 **Stage 2~4 LLM 프롬프트 변경** 수반(스키마 파싱 필수 — CLAUDE.md §6-6)

## 관련 패턴 / 과거 사례

- 현행 상세 페이지가 이미 티어 분기(`useTierCheck`)·판단 보류·면책·snake_case 대응을 구현 → **로직 재사용, 시각만 교체**가 최소 경로
- BE 응답 계약 SSOT: `AnalysisResponse.java`(NON_NULL 티어 화이트리스트). FE 타입 `disclosures.ts`와 1:1 — **신규 필드는 BE 먼저, FE는 BE Read 후 반영**(메모리: FE implement 전 BE Controller/DTO Read 필수)
- `financial_context`는 Stage 5 미구현으로 항상 null — Premium 카드는 목업대로 CTA만(잠금) 유지

## 리스크 / 법적 검토

- **자본시장법 §11.1**: "예측 차트"·"호재/악재 요인"은 투자 권유 표현으로 오독될 위험 → 면책 문구 상시 노출 유지, 단정적/권유 카피 금지("~에 유의하세요" 수준 정보 제공톤 유지)
- **LLM 환각**: 신규 요인/해설/예측은 반드시 스키마(Java record/Zod) 파싱 후 렌더, `confidence` 낮으면 판단 보류로 전 카드 대체
- **개인정보**: 내 평균 매수가는 AES-256 암호화 필드 — FE에 평문 노출/로깅 금지, 손익 계산은 최소 데이터만
- **접근성**: 호재/악재 요인은 색상 단독 금지 → 색 + 텍스트/아이콘 병용(색맹), 예측 차트 aria-label

## 권장 구현 방향

**단계 분할(lazy-first)** — 목업 전체를 한 번에 구현하지 말고, 데이터가 실제로 뒷받침되는 부분부터:

- **Wave 1 (FE-only, BE 무변경)**: 기존 응답 필드(summary/confidence/similar/sentiment)만으로 목업 시각 언어에 맞춰 레이아웃 재구성. 신규 데이터가 필요한 "이런 내용이에요"·"호재/악재 요인"·"매수영향 예측"·"내 평균 매수가"는 **이번 wave에서 렌더하지 않음**(가짜 플레이스홀더 금지 — 없는 필드를 지어내지 않는다).
- **Wave 2 (BE + LLM)**: `headline`·`key_points[]`·`positive/negative_factors[]` 신규 필드를 Stage 2~4 프롬프트+스키마로 추가 → AnalysisResponse/entity/api_spec 동기, FE 카드 추가. 저장은 `stageDetails` JSON 재활용 가능 여부 먼저 확인(불가 시 Flyway).
- **Wave 3**: forward 예측 차트 데이터 + 포트폴리오 평균 매수가 연동(개인정보 경로).

트레이드오프: 한 번에 목업을 다 그리면 Wave 2/3 데이터 없이 빈 카드가 남아 투자자에게 "분석이 있는 것처럼" 보이는 위험(자본시장법·환각 가드 위배). 데이터가 있는 것만 노출하는 점진 구현이 안전.

**확인 필요 항목**:
- 목업의 티어 경계(요인/예측/해설이 각각 Free/Pro/Premium 중 어디인지)
- `AnalysisResult.stageDetails` String의 현재 용도/구조 — 신규 필드 무마이그레이션 저장 가능 여부
- forward "예측" 값의 산출 주체(Stage 4 LLM vs 룰 vs 유사공시 평균 재활용)

## Tech Review (dc-tech-review · 2026-07-02)

### 아키텍처 분해
- 영향 레이어: **frontend(disclosures/[id])** 주도 · Wave 2+에서 **backend(analysis)** + LLM 프롬프트
- 신규 vs 수정:
  - 수정: `disclosures/[id]/page.tsx`(전면 리레이아웃), `ConfidenceMeter`·`PriceReactionChart`(재배치/확장), `disclosures.ts`(타입), `AnalysisResponse.java`·`AnalysisResult`·Stage 2~4 프롬프트/record
  - 신규: 요인 2컬럼·"이런 내용이에요"·매수가 박스용 FE 서브컴포넌트(page 내 or `components/domain/`)

### 조사로 해소된 "확인 필요" 항목
- ✅ **저장 방식**: `analysis_results.stage_details`가 **이미 JSONB 컬럼**(entity 매핑 존재, 현재 null). Wave 2 신규 필드(headline/key_points/factors)는 여기에 스키마 파싱 후 저장 → **Flyway 마이그레이션 불필요**. (db_schema.md §analysis_results, AnalysisResult.java:65-68)
- ✅ **매수가 데이터원**: `portfolios.ts`가 `avg_buy_price`(AES-256 복호화 후 number) 이미 노출 → Wave 3은 신규 암호화 경로가 아니라 stock_code 조인 + 현재가 소비. **평문 로깅 금지**(CLAUDE.md §7) 준수만 필요.

### 확정된 결정 (사용자 승인 · 2026-07-02)
- **티어 경계**:
  - **Free(Stage 2)**: AI 인덱스(confidence), 한 줄 요약, **"이런 내용이에요"(key_points)**, **호재/악재 요인 2컬럼** — 입문 투자자 보호(페르소나 E)
  - **Pro(Stage 3~4)**: **매수영향 예측 차트**, 과거 유사 공시
  - **Premium(Stage 5)**: 재무·업황(미구현, CTA만)
  - **티어 무관**: 내 평균 매수가 박스(보유 종목이면 노출)
  - → BE `AnalysisResponse` 화이트리스트: key_points/factors는 **모든 티어 포함**, 예측 차트 데이터는 **Pro+에만 포함**
- **예측값 산출 = 방식 A(유사공시 실측 일자평균)** 확정. LLM 미래 예측(방식 B) **비채택**(자본시장법·환각 리스크). 카피는 "예측" 단정 대신 **"과거 유사 사례 평균 등락"** 프레이밍 필수 → `similar_disclosures`를 D+1~D+5 일자별 평균으로 확장.

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| **Wave 1 — FE-only, BE 무변경** ||||||
| 1 | 상세 페이지 목업 레이아웃 재구성(카드/2컬럼/타이포, 기존 로직 유지) | frontend/disclosures | FE | 중 | - |
| 2 | AI 인덱스 % 상단 우측 배치(ConfidenceMeter 재활용) | frontend/disclosures | FE | 하 | 1 |
| 3 | 유사 공시 리스트+PriceReactionChart Pro 카드 재배치 | frontend/disclosures | FE | 하 | 1 |
| 4 | Premium 다크 CTA + FeedbackPrompt 재배치, 판단보류 회귀검증 | frontend/disclosures | FE | 하 | 1 |
| **Wave 2 — BE(JSONB+LLM) + FE** ||||||
| 5 | Stage 2 프롬프트+record에 headline/key_points[]/positive·negative_factors[] 추가, stage_details JSONB 저장 (모두 Free 노출) | backend/analysis | BE(LLM) | 상 | - |
| 6 | AnalysisResponse 신규 필드 노출 — key_points/factors 전 티어, 예측 데이터 Pro+ 화이트리스트 + api_spec §2.4 + disclosures.ts 동기 | backend/analysis, frontend | BE | 중 | 5 |
| 7 | "이런 내용이에요" 단계 카드 + 호재/악재 요인 2컬럼(색+아이콘 병용) — **Free** | frontend/disclosures | FE | 중 | 6 |
| **Wave 3 — 예측(Pro) + 포트폴리오(티어무관)** ||||||
| 8 | D+1~D+5 **유사공시 실측 일자평균** 산출(방식 A) → Pro 응답 포함 | backend/analysis | BE | 중 | 3 |
| 9 | 매수영향 예측 차트(PriceReactionChart 확장, "과거 유사 평균" 카피) — **Pro** | frontend/disclosures | FE | 중 | 8 |
| 10 | 내 평균 매수가 박스(portfolios avg_buy_price + 현재가 조인, 무로깅) — **티어 무관** | frontend/disclosures | FE | 중 | - |

### DB / 마이그레이션 영향
- **Wave 1~2: Flyway 불필요** — 신규 필드는 기존 `analysis_results.stage_details` JSONB 활용(스키마 파싱 후 저장, 환각 방지 원칙 준수).
- **Wave 3: Flyway 불필요 예상** — 예측값도 stage_details JSONB 확장. 단 예측을 별도 정규 컬럼으로 승격 결정 시에만 `V{n}__add_analysis_prediction.sql` 신설(현재 권장 안 함 — YAGNI).

### 외부 계약 영향
- DART/KRX/카카오 **불변**.
- **Stage 2~4 LLM 프롬프트 변경**(Wave 2~3) — 신규 출력 필드는 반드시 Java record/Zod 스키마 파싱 후 사용/저장(CLAUDE.md §6-6). 자체 REST `GET /disclosures/{id}/analysis` 응답 스키마 확장(api_spec §2.4 동기 필수).

### 리스크 & 법적 검토
- **자본시장법 §11.1**: 호재/악재 요인·예측 차트는 투자 권유로 오독 위험 → 정보 제공톤 유지("~에 유의" 수준), 단정·권유 카피 금지, 면책 상시 노출. 예측은 실측 근거(유사공시 평균) 우선 권장.
- **LLM 환각**: 신규 요인/해설/예측은 스키마 파싱 필수, `confidence<0.5`/`is_withheld` 시 전 카드 판단보류 대체(Wave 1에서 회귀 검증 — 카드 #4).
- **금융 개인정보**: 매수가 박스는 복호화 값 평문 로깅·클라 캐시 노출 금지(CLAUDE.md §7).
- **접근성**: 호재/악재 색상 단독 금지 → 색+텍스트/아이콘, 예측 차트 aria-label(WCAG 2.1 AA).

### 예상 wave 수
- **3 wave / 3 PR**. Wave 1(FE 단독, 즉시 착수 가능)만으로도 목업 시각 개선의 시각적 핵심 달성. Wave 2~3은 BE/LLM 선행 필요.

<!-- 다음: 사용자 승인 시 /dc-spec-move disclosure-detail-redesign Approved → /dc-implement -->
