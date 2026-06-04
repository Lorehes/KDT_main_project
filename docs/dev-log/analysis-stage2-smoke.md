---
type: doc
status: active
created: 2026-06-05
updated: 2026-06-05
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
