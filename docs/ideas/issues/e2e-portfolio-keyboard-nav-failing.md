---
type: issue
status: Open
created: 2026-07-05
updated: 2026-07-05
source: dc-test-verify (portfolios-recent-disclosures-5d 검증 중 발견)
priority: P2
---

> **상태**: Open — 2026-07-05 발견. main HEAD에서도 동일 실패(회귀 아님, 기존 결함).

# E2E `portfolios/keyboard-nav` (a)(b) 2건 실패 — 종목 검색 옵션 aria-selected 미갱신

## 현상

`frontend/e2e/portfolios/keyboard-nav.spec.ts`의 3개 시나리오 중 2개가 실패한다:

- **(a)** `ArrowDown + Enter → /portfolios/add 라우팅` (57:7)
- **(b)** `ArrowDown 순환 — 마지막 → 첫 번째` (78:7)
- (c) Escape → 드롭다운 닫힘 — **통과**

실패 지점(67행):

```
expect(page.getByRole("option").first()).toHaveAttribute("aria-selected", "true")
```

- 옵션 요소는 존재(`<li role="option" id="stock-option-0" aria-selected="false">`)하나, `ArrowDown` 후에도 `aria-selected`가 `"false"`로 남아 활성 표시가 갱신되지 않음.

## 재현 방법

```bash
cd frontend && pnpm exec playwright test e2e/portfolios/keyboard-nav.spec.ts
# → 2 failed, 1 passed
```

- BE는 `page.route()`로 전량 모킹(실 BE 불필요), 테스트 계정 쿠키는 fake JWT 주입.

## 기대 동작

`ArrowDown` 시 첫 번째 옵션의 `aria-selected="true"` + activedescendant가 갱신되어 키보드 네비게이션이 동작해야 한다 ([[portfolio-search-keyboard-nav]] Spec 인수 기준).

## 원인 범위 (미확정 — 조사 필요)

- **회귀 격리 완료**: 이번 작업(portfolios-recent-disclosures-5d) 변경분을 `git stash`한 순수 HEAD(71a2c54)에서도 (a)(b) 동일 실패 → **이번 변경과 무관한 기존 결함**.
- 후보: 종목 검색 드롭다운 컴포넌트의 키다운 핸들러가 옵션 렌더 이후 activeIndex를 반영하지 못하거나, Playwright 셀렉터가 최신 컴포넌트 구조(디바운스 훅 교체 등)와 어긋났을 가능성. 실제 UI 동작 여부부터 확인 필요(테스트만 깨진 것인지 기능도 깨진 것인지 구분).

## 맥락
- 출처: dc-test-verify (portfolios-recent-disclosures-5d 검증)
- 날짜: 2026-07-05
- 관련 레이어: Frontend
- 관련: [[portfolio-search-keyboard-nav]] (원 Spec)
