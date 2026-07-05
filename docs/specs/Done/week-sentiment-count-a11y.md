---
type: spec
status: Done
created: 2026-07-05
updated: 2026-07-05
---

# "이번 주 공시" 감성 카운트 접근성 — 색상 단독 → 색+라벨/아이콘 병기 Spec

> 상태: Approved → **Done** (2026-07-05, 구현·5페르소나리뷰·실앱검증 통과)

## 배경 / 목적

- `/portfolios`의 "이번 주 공시" StatCard(`frontend/src/app/(app)/portfolios/page.tsx:186-190`)가 호재/중립/악재 건수를 **색상만으로** 구분(`3/2/0`):
  ```tsx
  <span className="text-[color:var(--color-sentiment-positive)]">{weekPositive}</span>
  <span className="text-muted-foreground">/{weekNeutral}/</span>
  <span className="text-[color:var(--color-sentiment-negative)]">{weekNegative}</span>
  ```
- CLAUDE.md §6-5 "호재/악재 배지는 색상 단독 금지 → 색상 + 텍스트/아이콘 병용(색맹 배려)" 위반 소지. WCAG 2.1 AA(정보 전달을 색에만 의존 금지).
- 페르소나 C(시니어)·색맹 사용자에게 세 숫자의 감성 의미가 전달되지 않음.
- BM 티어: 전 티어 공통.

## 요구사항

- [ ] "이번 주 공시" 카운트에 색상 + **텍스트 라벨 또는 아이콘** 병기 (예: `호재 3 · 중립 2 · 악재 0` 또는 `▲3 ―2 ▼0`)
- [ ] 흑백/색맹 시뮬레이터에서도 세 값의 감성 구분이 유지
- [ ] 스크린리더용 `aria-label` 제공(예: "이번 주 공시 5건 · 호재 3 중립 2 악재 0")
- [ ] 기존 디자인 토큰(`--color-sentiment-*`)만 사용 — hex/임의 px 금지(§6-4)
- [ ] 좁은 카드 폭(2열 grid의 1칸)에서 레이아웃 깨짐 없이 수용

## 영향 범위 (조사 결과)

- 영향 레이어: **frontend** 단독. BE/DB/외부 계약 변경 없음
- 영향 파일:
  - `frontend/src/app/(app)/portfolios/page.tsx` — L164-178(카드 마크업). 집계 로직(L103-108 weekPositive/Neutral/Negative)은 변경 불필요
- DB 변경: 없음

## 관련 패턴 / 과거 사례

- `frontend/src/components/domain/StatCards.tsx:132` `SentimentStatCard` — 대시보드에서 이미 **색+라벨(호재/중립/악재/보류) 4분할**로 §6-5 준수. 톤 클래스(`text-[color:var(--color-sentiment-*)]`)·라벨 패턴을 그대로 참고
- `frontend/src/components/domain/SentimentBadge.tsx` — 색+텍스트+아이콘 병용 배지. 인라인 축약 표기의 아이콘 소스로 참고 가능
- `[[public-navbar-aria-labels]]`(Closed) — aria-label 누락 이슈 선례. 동일 접근성 축

## 리스크 / 법적 검토

- 접근성 개선이 목적이므로 리스크 낮음. 단, 좁은 카드에 라벨 4요소(건수 + 3감성)를 넣으면 폭 초과 가능 → 아이콘(▲/―/▼) 방식이 공간 효율적일 수 있음. 모바일 2열 grid에서 특히 확인 필요
- 자본시장법: 해당 없음 — 감성 카운트는 사실 집계 표시. 투자 권유 표현 아님
- 디자인 토큰(§6-4): 신규 색/간격 하드코딩 금지 — 기존 sentiment 토큰만 사용

## 권장 구현 방향

- **아이콘 병기(권장)**: 좁은 카드 폭 고려 시 `▲3 ―2 ▼0` 형태가 라벨보다 공간 효율적. 아이콘은 `aria-hidden`, 전체 값에 `aria-label`로 의미 제공(SentimentBadge가 이미 쓰는 방식과 일관)
- 대안: `호3·중2·악0` 축약 라벨 — 텍스트가 명시적이나 폭 부담. Tech Review에서 디자인 확정
- 확장성: 향후 "보류(withheld)" 카운트도 노출할 수 있으므로, 세그먼트 배열 구조로 작성해 항목 추가가 쉽게(SentimentStatCard의 segments 배열 패턴 차용)

## Tech Review (dc-tech-review · 2026-07-05)

### 아키텍처 분해
- 영향 레이어: **frontend** 단독. BE/DB/외부 계약 변경 없음
- 신규 vs 수정:
  - 신규: 없음 (기존 sentiment 토큰·아이콘 자산 재사용)
  - 수정: `portfolios/page.tsx:179-193`의 "이번 주 공시" 카드 인라인 카운트 마크업 1곳. 집계 로직(L106-108)은 불변
- 재사용 자산(확인 완료): `SentimentBadge`(색+lucide 아이콘+라벨+`aria-label` 3중 표기), `SentimentStatCard`(segments 배열 + 라벨), 토큰 `--color-sentiment-{positive,neutral,negative,withheld}` 모두 존재

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | 인라인 `3/2/0`을 색+아이콘(또는 축약 라벨) 병기로 교체 — 세그먼트 배열(호재/중립/악재) 구조로 작성해 확장 대비. 각 값에 `aria-hidden` 아이콘, 전체에 그룹 `aria-label` | frontend/portfolios | FE | 하 | - |
| 2 | 좁은 카드 폭(2열 grid 1칸)·모바일 레이아웃 검증 — 오버플로/줄바꿈 없이 수용. 디자인 토큰만 사용(§6-4), hex/임의 px 금지 | frontend/portfolios | FE | 하 | #1 |
| 3 | 접근성 회귀 확인 — 흑백/색맹 시뮬에서 3값 구분 유지 + 스크린리더 `aria-label` 낭독 확인(`/dc-review-frontend` 또는 수동) | frontend | FE | 하 | #1 |

### DB / 마이그레이션 영향
- 없음 — Flyway 마이그레이션 불필요

### 외부 계약 영향
- DART/KRX/카카오/LLM: 없음. 자체 REST: 없음 (표시 계층 전용)

### 리스크 & 법적 검토
- **의미 정합(주의)**: 현재 `weekNeutral` 집계(L107)는 `is_withheld || NEUTRAL`을 합산 → "중립" 칸에 **보류(withheld)가 포함**됨. `aria-label`/라벨 문구를 "중립"으로만 쓰면 보류 공시가 중립으로 오독될 수 있음. 문구를 "중립·보류" 또는 별도 칸 분리 중 택1 — 구현 시 확정(기본 제안: 현 집계 유지 + 라벨 "중립"에 보류 포함을 툴팁/aria로 보완, 4칸 분리는 폭 부담으로 비권장)
- **좁은 폭 리스크**: 라벨 4요소(건수+3감성 텍스트)는 2열 grid 1칸에 폭 초과 가능 → **아이콘(▲/―/▼) 방식이 공간 효율적**. `SentimentBadge`는 lucide 아이콘을 쓰나 이 카드는 초축약 인라인이므로 유니코드 화살표(▲/―/▼) + `aria-hidden`이 더 적합할 수 있음 — 디자인 확정 필요
- 자본시장법: 해당 없음 — 감성 카운트는 사실 집계 표시(투자 권유 아님). ▲/▼ 기호는 등락이 아닌 감성 분류 표식이므로 오해 방지 위해 `aria-label`에 "호재/악재"로 명시
- 디자인 토큰(§6-4): 신규 색·간격 하드코딩 금지 — 기존 sentiment 토큰만

### 예상 wave 수
- **1 wave** (FE 단일 파일, 소규모). 마크업 교체 + 접근성 검증

### 판정
- **구현 가능 (Approved 전환 권장)** — 소규모 접근성 개선, 구조적 리스크 없음. 확정 대기 2건은 구현 시 결정 가능:
  1. 표기 방식: 아이콘(▲/―/▼, **권장**) vs 축약 라벨(호3·중2·악0)
  2. "중립" 칸의 보류 포함을 문구/aria로 어떻게 드러낼지 (기본: aria-label 보완)
