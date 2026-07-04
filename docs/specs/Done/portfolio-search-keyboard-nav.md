---
type: spec
status: Done
created: 2026-06-22
updated: 2026-06-23
---

# 종목 검색 드롭다운 키보드 네비게이션 Spec

> 상태: Draft → Approved (2026-06-23) → **Done** (2026-06-23, 카드 #1~#5 구현 + dc-review-code A- + Playwright 3케이스 통과)

## 배경 / 목적

`/portfolios` 종목 검색 드롭다운(`role="combobox"` + `role="listbox"` + `role="option"`)이 마우스 전용으로 동작한다.
WAI-ARIA 1.2 Combobox Pattern spec은 ArrowDown/ArrowUp/Enter 키보드 네비게이션을 필수로 규정하며,
이를 미충족하면 스크린 리더·키보드 전용 사용자(페르소나 E — 시니어 투자자)가 종목 등록 불가.

- **영향 페르소나**: C(일반 투자자), E(시니어 투자자 — 키보드 의존)
- **규정 근거**: WCAG 2.1 AA (CLAUDE.md §6-5), WAI-ARIA 1.2 Combobox Pattern

## 요구사항

- [ ] ArrowDown: 드롭다운에서 다음 옵션으로 포커스 이동 (마지막 → 첫 번째 순환)
- [ ] ArrowUp: 이전 옵션으로 포커스 이동 (첫 번째 → 마지막 순환)
- [ ] Enter: 현재 활성 옵션을 선택 (`handleSelect` 호출)
- [ ] Escape: 드롭다운 닫기 (기구현)
- [ ] 드롭다운 열릴 때 `activeIndex = -1` 초기화 (입력 포커스 유지)
- [ ] 각 `role="option"`에 `aria-selected={activeIndex === i}` 동적 반영
- [ ] 활성 옵션에 시각적 강조 스타일 (`bg-muted` 또는 `ring-2 ring-primary/40`)
- [ ] input에 `aria-activedescendant={activeIndex >= 0 ? \`stock-option-\${activeIndex}\` : undefined}` 연결

## 영향 범위

- **영향 레이어**: frontend
- **영향 파일**:
  - `frontend/src/app/(app)/portfolios/page.tsx` — 인라인 드롭다운 (주 대상)
  - `frontend/src/components/domain/StockSearchCombobox.tsx` — 동일 ARIA 구조 사용 (병행 수정 권장)
- **DB 변경**: 없음
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- `frontend/src/components/domain/OTPInput.tsx` — 키보드 이벤트 핸들러 + `ref` 포커스 이동 패턴 참고
- `StockSearchCombobox.tsx` (line 88): `role="option" aria-selected={false}` — 현재 `aria-selected` 정적 false 하드코딩. 동일 이슈.
- WAI-ARIA APG Combobox Pattern: [https://www.w3.org/WAI/ARIA/apg/patterns/combobox/](https://www.w3.org/WAI/ARIA/apg/patterns/combobox/)

## 리스크 / 법적 검토

- 없음 (UI 접근성 개선, 데이터 처리 없음)

## 권장 구현 방향

### 접근법: `activeIndex` state + `onKeyDown` 확장

```tsx
const [activeIndex, setActiveIndex] = useState(-1);

// searchResults 변경 시 activeIndex 리셋
useEffect(() => { setActiveIndex(-1); }, [searchResults]);

const handleKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
  const len = searchResults?.length ?? 0;
  if (e.key === "ArrowDown") {
    e.preventDefault();
    setActiveIndex((i) => (i + 1) % len);
  } else if (e.key === "ArrowUp") {
    e.preventDefault();
    setActiveIndex((i) => (i - 1 + len) % len);
  } else if (e.key === "Enter" && activeIndex >= 0 && searchResults?.[activeIndex]) {
    e.preventDefault();
    handleSelect(searchResults[activeIndex]);
  } else if (e.key === "Escape") {
    setDropdownOpen(false);
  }
};
```

각 option에:
```tsx
<li
  id={`stock-option-${i}`}
  role="option"
  aria-selected={activeIndex === i}
  className={activeIndex === i ? "bg-muted" : ""}
>
```

input에:
```tsx
aria-activedescendant={activeIndex >= 0 ? `stock-option-${activeIndex}` : undefined}
```

### StockSearchCombobox 병행 수정

`StockSearchCombobox.tsx`도 동일 ARIA 구조를 가지므로 같이 수정하면 일관성 확보. 단, 이 컴포넌트는 현재 `portfolios/page.tsx`에서 사용하지 않으므로 우선순위 하위.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-23)

### ⚠️ Spec 전제 정정 (구현 전 필독 — 실제 코드 직접 확인 결과)

코드 직접 Read 결과 Spec의 대상 파일·구조 전제 3건이 현재 코드와 어긋남. **이 정정 없이 구현하면 엉뚱한 파일을 고치게 됨.**

1. **[치명] 주 대상 `portfolios/page.tsx`에는 검색 combobox가 더 이상 없다.**
   Spec §영향범위 line 36은 "`portfolios/page.tsx` — 인라인 드롭다운 (주 대상)"이라 했으나, 이 파일은 **대시보드로 개편**(line 3 주석 "단순 종목 등록 페이지 → 대시보드로 개편", 종목 등록은 `/portfolios/new`로 이동)되어 `role="combobox"`/`role="option"`이 전혀 없다(grep 0건).
   **실제 대상은 2곳:**
   - **`frontend/src/app/(app)/portfolios/new/page.tsx`** (line 92~159) — `useStockSearch` 직접 사용한 인라인 드롭다운. Spec 권장 코드의 변수명(`searchResults`·`handleSelect`·`dropdownOpen`·`setDropdownOpen`)이 **이 파일과 정확히 일치** → Spec 작성 시 실제로 본 파일은 여기였고, 이후 라우트 리팩터로 경로만 바뀜.
   - **`frontend/src/components/domain/StockSearchCombobox.tsx`** (line 88) — 재사용 컴포넌트. `aria-selected={false}` 하드코딩, 키보드 핸들러 없음.

2. **[중대] `StockSearchCombobox`는 "미사용·우선순위 하위"가 아니라 실사용 중이다.**
   Spec line 93~95는 "현재 `portfolios/page.tsx`에서 사용하지 않으므로 우선순위 하위"라 했으나, **`PortfolioSheet.tsx`(line 105)가 이 컴포넌트를 사용**하고, `PortfolioSheet`는 다시 **`notifications/page.tsx`·`signup/complete/page.tsx`에서 렌더**된다. → 시니어(페르소나 E) 키보드 사용자가 알림/온보딩 플로우에서 종목을 추가하는 경로이므로 **동급 우선순위**. 두 구현 모두 수정 대상.

3. **[중대] 옵션 선택이 `<li>` 직접 클릭이 아니라 내부 `<button>` 경유다.**
   Spec 권장 코드(line 80~86)는 `<li role="option">`에 직접 핸들러를 가정하나 실제 구조는 다름:
   - `new/page.tsx`: `<li role="option">` 안에 **"+추가" `<button>`** → `onClick={handleSelect(stock)}` → `/portfolios/add`로 **라우팅**. (`onMouseDown preventDefault`로 blur 방지 패턴 사용 중)
   - `StockSearchCombobox.tsx`: `<li>` 안에 **옵션 전체 `<button>`** → `onClick={handleSelect(stock)}` → `onSelect` 콜백.
   → Enter 키 핸들러는 `handleSelect(searchResults[activeIndex])` 호출이 맞으나(Spec 권장과 일치), `<li>`에 직접 onClick을 추가하지 말 것(중복 핸들러·접근성 트리 혼란). **기존 button 경로를 Enter로 트리거**만 하면 됨.

### 추가 확인 사항 (현재 코드 상태)

- 두 파일 모두 옵션 `<li>`에 **`id` 속성 없음** → `id="stock-option-${i}"` 추가 필요(`aria-activedescendant` 연결 대상).
- 두 파일 모두 input에 **`aria-activedescendant` 없음** → 추가 필요.
- `new/page.tsx` input은 `onKeyDown`에 **Escape만** 처리(line 98) → ArrowDown/ArrowUp/Enter 확장.
- `StockSearchCombobox.tsx` input은 **`onKeyDown` 자체가 없음**(line 59~70) → 신규 추가. `query !== debouncedQ` 디바운스 상태와 `data` 변경 시 `activeIndex` 리셋 필요.
- `add/page.tsx`는 query param 기반 등록 폼 — combobox 없음(grep 0건). 대상 아님.

### 아키텍처 분해
- **영향 레이어**: frontend (app/portfolios/new, components/domain). 백엔드·DB·외부 API 무관.
- **신규**: 없음.
- **수정**: `portfolios/new/page.tsx`(인라인 드롭다운), `StockSearchCombobox.tsx`(재사용 컴포넌트). 두 구현은 **중복**이나, 동작 차이(라우팅 vs 콜백, atLimit 처리, "+추가" 버튼 vs 행 전체 선택)로 인해 **이번 범위에서 통합하지 않음** — 각각 동일 키보드 패턴을 독립 적용.
- **Stage 파이프라인 영향**: 없음.

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `new/page.tsx`: `activeIndex` state + `searchResults` 변경 시 리셋 + `onKeyDown` 확장(ArrowDown/Up 순환·Enter→기존 `handleSelect` 트리거·Escape 유지) | frontend/portfolios | FE | 중 | - |
| 2 | `new/page.tsx`: 옵션 `<li>`에 `id="stock-option-${i}"`·`aria-selected={activeIndex===i}`·활성 강조(`bg-muted`) + input `aria-activedescendant` 연결 | frontend/portfolios | FE | 하 | #1 |
| 3 | `StockSearchCombobox.tsx`: `activeIndex` state + `data` 변경/`open` 토글 시 리셋 + `onKeyDown` 신규 추가(동일 키 패턴, Enter→`handleSelect`) | frontend/components | FE | 중 | - |
| 4 | `StockSearchCombobox.tsx`: 옵션 `<li>` `id`·`aria-selected` 동적·활성 강조 + input `aria-activedescendant` 연결 | frontend/components | FE | 하 | #3 |
| 5 | 활성 옵션 `scrollIntoView({block:"nearest"})` — 결과 최대 20건이라 드롭다운 스크롤 시 키보드 포커스가 화면 밖으로 나가지 않게(두 파일 공통) | frontend | FE | 하 | #2,#4 |
| 6 | (선택) Playwright 키보드 네비 회귀 테스트 — ArrowDown→Enter로 종목 선택·`/portfolios/add` 라우팅 검증 | frontend/e2e | FE | 중 | #1,#2 |

> 카드 #1·#2(new/page) 와 #3·#4(Combobox)는 **상호 독립** — 병렬 가능. #5는 양쪽 완료 후 공통 적용.

### DB / 마이그레이션 영향
- **없음.** UI 접근성 개선만. Flyway 변경 불필요.

### 외부 계약 영향
- **없음.** `GET /stocks/search` 응답 스키마·DART/KRX/카카오/LLM 모두 무관.

### 리스크 & 법적 검토
- **[a11y·핵심] 두 구현 누락 위험**: `StockSearchCombobox`(PortfolioSheet 경로)를 빼면 알림·온보딩 플로우의 키보드 사용자(페르소나 E)가 여전히 종목 추가 불가 → 카드 #3·#4 필수. (전제정정 #2)
- **[회귀] 마우스 hover 와 키보드 activeIndex 충돌**: 기존 `hover:bg-muted`와 `activeIndex` 강조가 동시 활성 시 시각적 혼선 가능 → 활성 옵션 스타일을 hover보다 우선(또는 동일 토큰)하게. 디자인 토큰만 사용(CLAUDE.md §6-4).
- **[회귀] `new/page.tsx` Enter 기본동작**: input이 `type="search"`라 Enter 시 폼 submit/검색 트리거 가능 → `activeIndex>=0`일 때 `e.preventDefault()`로 차단(Spec 권장 코드에 포함됨).
- **자본시장법/개인정보**: 해당 없음 — 매수가·수량 비노출, 검색은 공개 종목 마스터(stocks)만 조회.
- **WCAG 2.1 AA 근거**: `design_structure.md §6`(키보드: Tab 도달·Esc 닫기·Enter 실행, `:focus-visible` 링) + WAI-ARIA 1.2 Combobox Pattern. 본 Spec이 이 요구의 미충족분(ArrowKey·activedescendant)을 보완.

### 예상 wave 수
- **단일 wave 권장.** 6개 카드 모두 FE, 상호 의존 얕음. #1~#5를 한 PR로 묶고 #6(E2E)은 동봉 또는 후속 소형 PR. 총 **1 wave(+선택 테스트)**.

### Spec 상태 전환 제안
검토 결과 **구현 가능**(전제 3건 정정 반영 조건). 작업 카드·리스크·테스트 보강 완료 → `/dc-spec-move portfolio-search-keyboard-nav Approved` 권장.
