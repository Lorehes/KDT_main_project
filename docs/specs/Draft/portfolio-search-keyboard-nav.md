---
type: spec
status: Draft
created: 2026-06-22
updated: 2026-06-22
---

# 종목 검색 드롭다운 키보드 네비게이션 Spec

> 상태: **Draft** (dc-review-frontend 리뷰 결과 → dc-plan 생성)

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
