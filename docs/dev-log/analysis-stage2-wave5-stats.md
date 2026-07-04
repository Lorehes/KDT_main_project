---
type: doc
status: active
created: 2026-06-05
updated: 2026-06-05
related:
  - "[[analysis-stage2-llm]]"
  - "[[analysis-stage2-smoke]]"
---

# Wave 5 — Stage 2 Backfill 500건 통계 측정 (2026-06-05)

> **목적**: [[analysis-stage2-llm]] Spec §7 G3·G4 검증 + Tech Review §7 wave 5 산출물.
> **방법**: id 1~500 (2023-06~08 시즌 공시) 백필 실행 후 `analysis_results` 통계 SQL 측정.
> **결론**: 신뢰도/withheld 비율 목표 통과. 단, **모델 자체 정확도 우려**(99% NEUTRAL) 명백.

---

## 1. 백필 잡 정보

- jobId: `7f0c480f-61ed-4c82-bccd-2b786f260375`
- 범위: disclosure id 1~500
- chunkSize: 50 (10 청크)
- 모델: `qwen3:4b`
- 시작: 2026-06-05 01:00:11 KST
- 종료: 2026-06-05 01:36:50 KST (**36분 39초**)
- 결과: `analyzed=500 / failed=0` ✅

## 2. 성능 측정

| 지표 | 값 | 기준 |
|------|-----|------|
| 평균 응답 시간 | **4.39초/건** | smoke test 3.13초 + Spring overhead/DB I/O |
| p50 | 4.45초 | |
| p95 | 4.96초 | |
| 전체 처리량 | 500건 / 36.5분 = **13.7건/분** | 1 worker 활성 |
| 기획서 §6.3 목표 | 3~15초/Stage | ✅ 통과 |

> **인사이트**: `analysisBackfillExecutor`(core 1 / max 2)가 청크 내 순차 처리라 실질 1 worker만 활성.
> 91k 전건 예상: 91,989 × 4.39초 = **약 112시간 (4.7일)** — 단일 인스턴스로는 비현실적.
> 후속 개선: 청크 내 병렬화 또는 worker 풀 확장(Ollama RPS 한계 측정 필요).

## 3. Sentiment 분포

| Sentiment | 건수 | 비율 |
|-----------|------|------|
| NEUTRAL | **495** | **99.0%** |
| NEGATIVE | 5 | 1.0% |
| POSITIVE | 0 | 0.0% |

> **분석**: smoke test 패턴 그대로 — qwen3:4b는 **호재 단정 절대 회피**. NEGATIVE 5건은 모두 명백한 악재(아래 §5)로 모델 인식 정확. 모호한 케이스는 모두 NEUTRAL로 보수.

## 4. Withheld 비율 (Spec §7 G4)

| is_withheld | 건수 | 비율 |
|-------------|------|------|
| false | 496 | 99.2% |
| **true** | **4** | **0.8%** |

> **Spec G4 목표 ≤30%**: ✅ 통과 (0.8% << 30%).
> 단, 모든 NEUTRAL이 임계 0.6 이상이라 withheld 가드가 사실상 작동 안 함 — confidence 분포가 0.7 근처 집중(§5).

## 5. Confidence 분포

| 지표 | 값 |
|------|-----|
| 평균 | 0.710 |
| min | 0.700 |
| max | 0.850 |
| p50 | 0.700 |
| p95 | 0.800 |

> **분석**: 모델이 사실상 단일 패턴(NEUTRAL + ~0.7)에 수렴. 신뢰도 다양성 부족 → 임계 기반 withheld 가드의 의미가 약함. **모델 자체의 분류 자신감 캘리브레이션 한계**.

## 6. NEGATIVE 5건 — 모델 정확도 검증

| id | 회사 | 공시 | 통상 의미 | 모델 판단 |
|----|------|------|----------|----------|
| 8 | 한국앤컴퍼니 | 생산중단(자회사) | 악재 ✅ | NEGATIVE 0.700 |
| 9 | 한국타이어앤테크놀로지 | 생산중단 | 악재 ✅ | NEGATIVE 0.700 |
| 29 | SK바이오사이언스 | COVID-19 백신 EMA 허가 신청 철회 | 악재 ✅ | NEGATIVE 0.750 |
| 38 | 두산밥캣 | 해산사유발생(자회사) | 악재 ✅ | NEGATIVE 0.850 |
| 159 | iM금융지주 | 소송 판결(자회사) | 통상 악재 ✅ | NEGATIVE 0.700 |

→ **모델이 명백한 악재는 잘 인식**. 단정 시 정확도 5/5.

## 7. Withheld 4건 — PromptGuard False Positive 발견

| id | 유형 | sentiment | confidence | summary 키워드 |
|----|------|-----------|-----------|---------------|
| 14 | RELATED_PARTY_TRANSACTION | NEUTRAL | 0.700 | "특수관계인으로부터 기타유가증권**매수**" |
| 119 | STOCK_OPTION | NEUTRAL | 0.700 | "주식**매수**선택권부여" |
| 405 | RELATED_PARTY_TRANSACTION | NEUTRAL | 0.700 | "기타유가증권**매수**" |
| 499 | STOCK_OPTION | NEUTRAL | 0.700 | "주식**매수**선택권부여" |

> ⚠️ **PromptGuard 키워드 매칭의 False Positive**:
> "매수"는 "매수 권유"(자본시장법 위반)와 "매수 보고서/취득" 같은 사실 진술을 구분 못함.
> 4건 모두 confidence 0.7 임계 통과인데 키워드 매칭으로 withheld 강제 → 정상 분류가 차단됨.

### 조치 제안

`PromptGuard.FORBIDDEN_PATTERNS` 보강:
- ❌ 단순 "매수"/"매도" 제거 (false positive 많음)
- ✅ "매수\\s*추천", "매도\\s*권유", "사세요", "파세요", "수익\\s*보장", "확정\\s*수익" 등 **권유 맥락 한정 키워드**만 유지

## 8. disclosure_type별 분포 (상위 10)

| 유형 | 건수 | NEUTRAL | NEGATIVE | withheld |
|------|------|---------|----------|----------|
| CONGLOMERATE_DISCLOSURE | 140 | 140 | 0 | 0 |
| PROSPECTUS | 52 | 52 | 0 | 0 |
| DERIVATIVE_ISSUANCE | 47 | 47 | 0 | 0 |
| SECURITIES_ISSUANCE | 37 | 37 | 0 | 0 |
| LARGE_STAKE_CHANGE | 33 | 33 | 0 | 0 |
| EXECUTIVE_SHARE | 32 | 32 | 0 | 0 |
| IR_EVENT | 26 | 26 | 0 | 0 |
| OTHER | 26 | 24 | 2 | 0 |
| SUPPLY_CONTRACT | 18 | 18 | 0 | 0 |
| ANNUAL_REPORT | 12 | 12 | 0 | 0 |

> **인사이트**: 상위 9개 유형이 모두 100% NEUTRAL. CONGLOMERATE_DISCLOSURE(대기업집단공시) 같은 정보성 공시는 실제로 NEUTRAL이 합리적. 단 TREASURY_STOCK(4건 모두 NEUTRAL), BONUS_ISSUE(1건 NEUTRAL) 등 호재성 공시도 NEUTRAL — 모델 한계.

## 9. 종합 평가 + 후속 액션

### ✅ 검증된 항목

- 백필 파이프라인 정상 — analyzed 500 / failed 0 / chunks 10/10 SUCCEEDED
- 응답 시간 4.39초/건 — 기획서 §6.3 목표(3~15초) 통과
- withheld 비율 0.8% — Spec G4 목표(≤30%) 통과
- 명백한 악재 5건 정확 인식

### ⚠️ 식별된 리스크

1. **모델 자체 정확도 한계** — POSITIVE 단정 0건. 호재성 공시도 NEUTRAL로 회피. Stage 2 단독으로는 통합기획서 §1 가치 명제 약함. **Stage 3 RAG + Stage 4 LLM 2차 분석 필수**(별도 Spec).
2. **PromptGuard False Positive** — "매수"가 사실 진술에서 매칭. 권유 맥락 한정 키워드로 좁혀야 함.
3. **백필 throughput** — 단일 worker 4.39초/건, 91k = 4.7일. 청크 내 병렬화 또는 worker 풀 확장 후속 검토.
4. **응답 패턴 수렴** — confidence 99% 0.7 근처. 임계 기반 withheld 가드의 의미 약함. SystemConfig로 임계 조정 효과 제한적.

### 후속 카드 제안

- [ ] **wave 5.1 — PromptGuard 키워드 보강** (단어 → 권유 맥락 정규식). PromptGuardTest 보강
- [ ] **Stage 3 Spec** — Chroma 임베딩 + KRX 5일 반응 결합 RAG로 모델 정확도 보강
- [ ] **wave 5.2 — 청크 내 병렬화** 검토 (Ollama RPS 측정 → worker 풀 활용)
- [ ] 91k 전건 백필은 위 개선 후 결정 (현재 비용 대비 가치 낮음 — 99% NEUTRAL 분류 양산)

## 10. 재현 방법

```bash
# 사전: 백엔드 가동 + Ollama qwen3:4b 풀링
ollama list  # qwen3:4b 확인

# 백필 트리거
ADMIN_PASS=$(grep "^ADMIN_PASSWORD" .env | cut -d= -f2-)
curl -u "admin:$ADMIN_PASS" -X POST \
  "http://localhost:8080/admin/analysis/backfill/jobs?idFrom=1&idTo=500&chunkSize=50"

# 진행률 폴링 (jobId는 응답에서)
curl -u "admin:$ADMIN_PASS" \
  "http://localhost:8080/admin/analysis/backfill/jobs/<jobId>"

# 통계 쿼리
docker exec dartcommons-postgres psql -U dartcommons -d dartcommons -c "
SELECT sentiment, COUNT(*) FROM analysis_results GROUP BY sentiment;"
```
