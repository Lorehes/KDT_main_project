---
type: issue
status: Closed
created: 2026-07-05
updated: 2026-07-05
resolved: 2026-07-05
source: dc-review-frontend (portfolios-recent-disclosures-5d 리뷰)
priority: P2
---

> **상태**: Closed (→ Spec) — 2026-07-05 `docs/specs/Draft/week-sentiment-count-a11y.md` 승격.
> 다음 단계(Approved): `/dc-implement week-sentiment-count-a11y`

# /portfolios "이번 주 공시" 3/2/0 카운트가 색상 단독으로만 호재·중립·악재를 구분

## 현상

`frontend/src/app/(app)/portfolios/page.tsx:186-190`의 "이번 주 공시" StatCard에서 호재/중립/악재 건수가 **색상만으로** 구분된다:

```tsx
<span className="text-[color:var(--color-sentiment-positive)]">{weekPositive}</span>
<span className="text-muted-foreground">/{weekNeutral}/</span>
<span className="text-[color:var(--color-sentiment-negative)]">{weekNegative}</span>
```

- 화면에는 `5 건 · 3/2/0` 형태로 표시되며, 3(호재)·2(중립)·0(악재)의 의미가 오직 글자색(positive/muted/negative)으로만 전달됨.
- 텍스트 라벨·아이콘·툴팁 등 색맹 사용자를 위한 보조 단서가 없음.

## 기대 동작

CLAUDE.md §6-5 "호재/악재 배지는 색상 단독 금지 → 색상 + 텍스트/아이콘 병용"에 따라, 각 카운트에 라벨(예: `호재 3 · 중립 2 · 악재 0`) 또는 아이콘(▲/―/▼)을 병기해야 한다. 같은 페이지의 `SentimentBadge`는 이미 색+텍스트 병용을 준수하므로 그 패턴을 참고.

## 재현 방법

- `/portfolios` 로그인 후 상단 "이번 주 공시" 카드 확인 (건수 2개 이상 종목 보유 시 3/2/0 표시).
- 흑백/색맹 시뮬레이터로 보면 세 숫자의 감성 구분이 사라짐.

## 맥락
- 출처: dc-review-frontend (portfolios-recent-disclosures-5d 리뷰, frontend-design-reviewer top_issue)
- 날짜: 2026-07-05
- 관련 레이어: Frontend (접근성)
- 참고: 이번 변경(최근 5일) 이전부터 존재하던 카드 — 이번 리뷰에서 표면화됨. WCAG 2.1 AA / 페르소나 C(시니어)·색맹 배려.
