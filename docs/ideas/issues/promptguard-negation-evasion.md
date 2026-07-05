---
type: issue
status: open
created: 2026-07-06
updated: 2026-07-06
---

# [이슈] PromptGuard 부정문·우회 표현 미탐 (정규식 가드의 구조적 한계)

> 상태: **Open** — known limitation. 다층 방어(L1/L3/L4)로 커버 중이라 즉시 수정 불요.
> 트리거 조건 충족 시(하단) 패턴 보강.

## 현상

`PromptGuard.FORBIDDEN_PATTERNS`(정규식 기반)는 권유 맥락 결합 패턴만 차단한다.
문장의 **의미**를 해석하지 못하므로 아래 유형이 탐지망을 벗어난다:

| 유형 | 예시 | 현재 동작 |
|------|------|----------|
| 우회 표현 | "매수 포지션 구축이 유리해 보입니다" | 통과 (패턴에 없는 조합) |
| 완곡 권유 | "지금이 진입 적기로 판단됩니다" | 통과 |
| 부정문 | "매수를 권유하지 않습니다" | 어순 따라 오탐 차단 또는 통과 (비결정적) |

## 발견 경위

`analysis-stage4-llm-final` 구현 `/dc-review-code` S-3 (Low) — Stage 4 `rationale` 가드
(`isRationaleViolation`) 확장 시 정규식 방식의 구조적 한계 재확인. Stage 2 `sanitize` 경로도 동일.

## 영향 범위

- `backend/src/main/java/com/dartcommons/analysis/services/PromptGuard.java` — `FORBIDDEN_PATTERNS`
- 사용처: Stage 2 `sanitize()` (summary·key_points·요인) + Stage 4 `isRationaleViolation()` (rationale)

## 즉시 수정하지 않는 이유 (다층 방어)

| 층 | 방어 | 위치 |
|----|------|------|
| L1 | 프롬프트에 권유 표현 금지 명시 | `Stage2PromptBuilder` / `Stage4PromptBuilder` |
| L2 | 응답 후처리 정규식 가드 (본 이슈 대상) | `PromptGuard` |
| L3 | 신뢰도 임계 미만 → "판단 보류" | confidence threshold |
| L4 | 면책 문구 + 신고 경로 상시 동반 | `AnalysisResponse.DISCLAIMER` |

L2가 놓쳐도 L4가 "투자 자문 아님"을 상시 표방 — 자본시장법 리스크의 핵심(자문 표방)을 차단.
또한 선제적 패턴 확장은 오탐 부작용이 실증됨: bare `매수/매도` 패턴이 법률 용어
(주식매수청구권·자기주식취득·매수세)에 오탐 폭증 → [[promptguard-legal-term-false-positive]]로 재정의한 이력.
**패턴 추가는 운영 실측 기반으로만** (PromptGuard 머리 주석 원칙).

## 재검토 트리거 (이 중 하나라도 발생 시)

1. **LLM 모델 교체** — 모델별 말투 상이. 교체 후 표본 응답(수백 건)에서 권유형 표현 스캔 필수.
2. **운영 중 미탐 실측** — 사용자 신고("부정확함" 경로) 또는 로그 모니터링에서 우회 표현 발견.
3. **Stage 5 도입** — 재무/업황 해석 텍스트가 추가되면 노출 텍스트 표면적 증가 → 가드 대상 재점검.

## 보강 방향 (재검토 시 선택지)

- **A. 패턴 보강** (저비용): 실측된 우회 표현을 맥락 결합 정규식으로 추가. 오탐 회귀 테스트(`PromptGuardTest` 양방향) 필수.
- **B. LLM 셀프 체크** (중비용): 별도 경량 프롬프트로 "이 텍스트가 투자 권유인가" 2차 판정. LLM 호출 +1 → 예산 영향.
- **C. 분류기 도입** (고비용): 권유/비권유 이진 분류 모델. MVP 범위 밖.

권장: 트리거 발생 시 A부터. B는 유료 LLM 전환 후 검토.

## 관련

- [[analysis-stage4-llm-final]] — 발견 경위 Spec
- [[promptguard-legal-term-false-positive]] — 과보수 패턴의 오탐 사고 이력 (반대 방향 교훈)
- 통합기획서 §11.1 — 자본시장법 표현 가이드라인
