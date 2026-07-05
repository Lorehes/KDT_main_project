---
type: issue
status: Closed
created: 2026-07-05
updated: 2026-07-05
resolved: 2026-07-05
source: dc-review-frontend (dashboard-recent-3days 리뷰)
priority: P3
---

> **상태**: Closed (→ Spec) — 2026-07-05 `docs/specs/Draft/disclosure-date-format-unify.md` 승격 (시연 크리티컬로 상향).
> 다음 단계(Approved): `/dc-implement disclosure-date-format-unify`

# DisclosureCard가 공시 날짜를 원시 포맷(YYYYMMDD)으로 노출 — 대시보드에서 "20260705" 그대로 표시

## 현상

`frontend/src/components/domain/DisclosureCard.tsx:69-70`이 `rcept_dt`를 가공 없이 렌더한다:

```tsx
<time className="text-xs text-muted-foreground" dateTime={disclosure.rcept_dt}>
  {disclosure.rcept_dt}
</time>
```

- DART `rcept_dt`는 `"20260705"`(YYYYMMDD) 문자열 → 화면에 하이픈·구분 없이 `20260705`로 노출.
- 대시보드 공시 피드가 이 카드를 사용하므로 각 행 날짜가 원시 포맷으로 보임.
- 반면 `/portfolios`의 "종목별 최근 공시" 패널은 `formatRelativeTime()`으로 `"7시간 전"` 상대시간을 표시 → **같은 데이터가 화면마다 다른 포맷**.

## 기대 동작

`DisclosureCard`도 `YYYY-MM-DD` 또는 상대시간("N시간 전")으로 정규화해 표시하고, 앱 전반의 날짜 표기를 통일한다. `portfolios/page.tsx`의 `formatRelativeTime` 로직을 공용 유틸로 승격해 재사용하는 방향 검토.

## 재현 방법

- `/dashboard` 로그인 후 "최신 공시" 피드의 각 공시 행 우측 날짜 확인 → `20260705` 형태.

## 맥락
- 출처: dc-review-frontend (dashboard-recent-3days 리뷰, frontend-design-reviewer)
- 날짜: 2026-07-05
- 관련 레이어: Frontend
- 참고: DisclosureCard는 공유 컴포넌트 — 수정 시 대시보드·공시 피드 등 사용처 전반에 영향(회귀 확인 필요). 이번 변경 이전부터 존재하던 표기.
