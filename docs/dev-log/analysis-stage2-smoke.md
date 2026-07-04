---
type: doc
status: active
created: 2026-06-05
updated: 2026-07-02
related:
  - "[[analysis-stage2-llm]]"
  - "[[DART공시통역_통합기획서]]"
---

# Stage 2 Smoke Test — qwen3:4b vs gemma3:4b 비교 (2026-06-05)

> **목적**: [[analysis-stage2-llm]] Spec Tech Review T16 + 결정 4(MVP 모델 선택) 확정.
> **방법**: 실 DART 공시 5건(다양한 disclosure_type) 메타만 사용해 Ollama API 직접 호출, JSON 응답 파싱.
> **결과**: **qwen3:4b 확정** — gemma3가 명백한 오분류(유상증자 → POSITIVE) 위험.

---

## 1. 테스트 환경

- Hardware: Apple M4, Metal GPU, 25 GiB VRAM
- Ollama: 0.30.4
- 모델: qwen3:4b (2.5GB, Q4_K_M, 컨텍스트 262k) / gemma3:4b (3.3GB)
- 옵션: `temperature=0.2`, `num_predict=400`, `format=json`, `think=false` (qwen3 thinking 모드 비활성 필수)

## 2. 테스트 공시 (오늘치, disclosure_type 다양화)

| # | 회사 | 보고서 제목 | 분류값 | 실제 통상 의미 |
|---|------|------------|--------|---------------|
| 1 | 달바글로벌 | [기재정정]주요사항보고서(자기주식취득신탁계약체결결정) | TREASURY_STOCK | 약 호재 (자사주 매입 신호) |
| 2 | 다우기술 | [첨부정정]주요사항보고서(회사합병결정) | MERGER | 케이스별 (대체로 호재) |
| 3 | 삼현 | 자기주식취득결과보고서 | TREASURY_STOCK | 호재 (실제 매입 완료) |
| 4 | 클로봇 | [기재정정]주요사항보고서(유상증자결정) | RIGHTS_OFFERING | **악재** (주주가치 희석) |
| 5 | NHN | 자기주식취득결과보고서 | TREASURY_STOCK | 호재 |

## 3. 결과 — qwen3:4b

```
5건, 평균 3129ms (기획서 §6.3 목표 3000~15000ms)
```

| # | 결과 | confidence | 평가 |
|---|------|-----------|------|
| 1 | NEUTRAL | 0.85 | 보수적 |
| 2 | NEUTRAL | 0.75 | 보수적 |
| 3 | NEUTRAL | 0.75 | 보수적 |
| 4 | NEUTRAL | 0.75 | 정답 회피 (악재 단정 안 함) — 안전 |
| 5 | NEUTRAL | 0.85 | 보수적 |

→ **모든 케이스 NEUTRAL** + 임계치(0.6) 초과로 `is_withheld=false`. summary는 "TREASURY_STOCK을 기재했습니다" 같이 영어 분류값 노출 부자연.

## 4. 결과 — gemma3:4b

```
5건, 평균 ~3668ms
```

| # | 결과 | confidence | 평가 |
|---|------|-----------|------|
| 1 | NEUTRAL | 0.85 | 보수적 |
| 2 | **POSITIVE** | 0.85 | 합병→호재 단정 |
| 3 | NEUTRAL | 0.85 | 보수적 |
| 4 | **POSITIVE** | 0.85 | ❌ **유상증자를 호재로 오분류 + summary에 "주가에 긍정적인 영향" 명시** |
| 5 | NEUTRAL | 0.85 | 보수적 |

## 5. 결정 및 근거

### qwen3:4b 확정

| 기준 | qwen3:4b | gemma3:4b |
|------|----------|-----------|
| 평균 응답 시간 | **3,129ms** ✅ | 3,668ms |
| 명백한 오분류 | 0건 | **1건 (유상증자 → POSITIVE)** ❌ |
| 자본시장법 위반 표현 | 0건 | "주가에 긍정적인 영향" 1건 (summary 직접 포함) |
| 한국어 자연스러움 | 보통 (영어 enum 노출) | 약간 우수 |

**투자자 보호 관점에서 qwen3 우위**. gemma3는 더 자신감 있지만 잘못된 단정으로 인한 손실 책임 위험.

### 별도 인정 리스크

qwen3가 모두 NEUTRAL인 것은 **모델 자체 정확도 한계**다. Stage 2 단독으로는 호재/악재 가치 명제(통합기획서 §1) 충족 불가. **Stage 3 RAG + Stage 4 LLM 2차 분석에서 보강 필요** — [[analysis-stage2-llm]] §8 범위 외 항목으로 명시.

Free 사용자 노출 효과는 "최소 정보 노출 + 면책 강조" 모드로 작동(잘못된 호재/악재 단정보다 NEUTRAL이 안전).

## 6. 후속 액션

- [ ] Spec [[analysis-stage2-llm]] 결정 4 → `qwen3:4b` 확정 갱신
- [ ] `.env.example` LLM_MODEL을 `qwen3:4b`로 갱신 (기존 `qwen2.5:7b-instruct`)
- [ ] 프롬프트 개선 — "분류값(TREASURY_STOCK 등)을 한국어로 풀어 설명"하도록 유도 (영어 enum 노출 방지)
- [ ] wave 3에서 Orchestrator로 자동 연결 + 91k 백필 적용 시 신뢰도/분류 분포 통계 측정 (G4)
- [ ] Stage 3 후속 Spec: 유사 공시 임베딩 + KRX 5일 반응 결합으로 정확도 보강

## 7. 재현 방법

```bash
# 모델 풀링
ollama pull qwen3:4b
ollama pull gemma3:4b

# 백엔드 가동 (Postgres 연결)
cd backend && ./gradlew bootRun

# 별도 터미널에서 smoke test
bash scripts/analysis/stage2_smoke_test.sh qwen3:4b
bash scripts/analysis/stage2_smoke_test.sh gemma3:4b
```

스크립트는 DB에서 다양한 disclosure_type 5건을 자동 선택 → Ollama 호출 → 표 출력.

---

# Wave 2 재검증 — key_points/요인 생성 품질 (2026-07-02, disclosure-detail-redesign)

> **목적**: Wave 2가 Stage 2에 추가한 `key_points`/`positive_factors`/`negative_factors` 생성 품질을 실 LLM으로 확인.
> **방법**: `Stage2PromptBuilder.build()` 프롬프트를 그대로 재현(신규 스키마 포함), 공시 3건(CB발행/공급계약/무상증자) 메타만으로 Ollama `/api/generate`(format=json, think=false, num_predict=800) 호출.
> **결과**: **배관 ✅ (양 모델 스키마·JSON·금지키워드 통과), 그러나 모델 선택 딜레마 표면화.**

## 발견 — 2026-06-05 결론과 상충

| 항목 | qwen3:4b | gemma3:4b |
|------|----------|-----------|
| JSON 스키마/파싱 | ✅ | ✅ |
| 금지 키워드 위반 | none | none |
| **sentiment 분류** | ✅ 보수적(전건 NEUTRAL/0.7) | ⚠️ 낙관 편향(전건 POSITIVE/0.8) — 2026-06-05과 동일 |
| **key_points 사실 정확도** | ❌ **날짜·회사명 손상**("2과 20260527", "2026070과", "셀트리:") — [[CLAUDE]] §4 위반 | ✅ 깨끗·완결 |
| **요인 생성 풍부도** | ❌ 빈약(3건 중 요인 1개) | ✅ 풍부(삼성 호재3+악재3) |

### 핵심 딜레마
- **2026-06-05**: gemma 낙관편향(유상증자→POSITIVE) 때문에 **qwen3:4b 확정**. 당시엔 sentiment 분류만 평가(key_points/요인 없었음).
- **Wave 2 신규 필드는 정반대**: 분류 잘하는 qwen이 **key_points에서 날짜·회사명을 손상**(투자자에게 틀린 사실 노출 — §4 위반, 투자자 보호 리스크). 생성 잘하는 gemma는 낙관편향으로 sentiment 오분류.
- 즉 **하나의 4B 모델로 분류 정확도 + 생성 정확도를 동시에 만족 못 함.**

### 부가 관찰 (gemma)
- 일부 요인이 전망성 문구("주가 상승에 대한 긍정적 기대감 증폭") — 금지 키워드 리스트엔 안 걸리나 자본시장법 §11.1 경계(security 리뷰 #8 detection-completeness 갭). 프롬프트 "전망·추측 금지" 강화 여지.

### config 불일치 (기존)
- `application.yml` LLM_MODEL 기본값 = `google/gemma-3-4b-it:free`(OpenRouter) — 본 smoke 문서 2026-06-05의 "qwen3:4b 확정"과 **불일치**. llm-production-switch Spec이 OpenRouter 전환 시 gemma 기본으로 바꾼 것으로 보임. 어느 쪽이 SSOT인지 확정 필요.

## 미결정 (사용자 결정 필요)
1. Wave 2 요인/해설의 모델 전략: (a) 분류=qwen 유지 + key_points §4 손상 감내 불가 → 프롬프트로 "날짜·회사명 원문 복사" 강제 재시도, (b) gemma로 전환 + 낙관편향 완화 프롬프트, (c) 분류/생성 모델 분리, (d) 후보 모델 확대(gemma3:12b 등).
2. config vs smoke 문서 SSOT 불일치 해소.
3. 프롬프트 A/B(전망 금지 강화) — [[CLAUDE]] 규칙상 통계 기반 변경.
