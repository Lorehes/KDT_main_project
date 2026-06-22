---
type: issue
status: Open
created: 2026-06-22
updated: 2026-06-22
source: dc-review-frontend (portfolios 리팩터링 후 UI 리뷰)
priority: P3
---

# /portfolios/add 알림 토글 행 scrollWidth 11px 초과

> **상태**: Open — 시각 클리핑 없음, bodyOverflow false. P3 기술 부채.

## 현상

Playwright `scrollWidth > clientWidth + 5` 검사에서 `/portfolios/add` 알림 공시 종류 패널의 토글 행 3개 + 부모 컨테이너(총 4건) 에서 각 11px overflow 감지.

```
{ tag: "DIV", cls: "flex flex-col divide-y divide-border", diff: 11 }
{ tag: "DIV", cls: "flex items-center justify-between gap-3 py-3.5 ...", diff: 11 }  ×3
```

- `bodyOverflow: false` — 페이지 수평 스크롤 없음
- 콘솔 에러 없음, 시각적 클리핑 없음

## 원인 분석

`Switch` 컴포넌트(`frontend/src/components/ui/switch.tsx`)가 내부적으로 확장 터치 영역을 위해 `after` pseudo-element 사용:

```tsx
// switch.tsx:19
"after:absolute after:-inset-x-3 after:-inset-y-2 ..."
```

- `after:-inset-x-3` = `left: -12px; right: -12px` → 터치 타깃 양쪽 12px 확장
- Switch root가 `relative`이므로 after는 Switch 기준 배치되지만, 부모 flex 컨테이너에 `overflow: hidden`이 없어 `scrollWidth` 계산에 반영
- 실제 렌더링 픽셀 클리핑은 없으나 브라우저 scrollWidth 측정상 11px 초과

## 영향 파일

- `frontend/src/app/(app)/portfolios/add/page.tsx` — 알림 토글 섹션
- `frontend/src/components/ui/switch.tsx` — after 터치 확장 원천

## 수정 방향 (검토 필요)

### 옵션 A — 컨테이너에 `overflow-hidden` 추가 (빠르지만 부작용 있음)
```tsx
// 현재
<div className="flex flex-col divide-y divide-border">
// 수정안
<div className="flex flex-col divide-y divide-border overflow-hidden">
```
- **부작용**: Switch의 `:focus-visible` ring (`focus-visible:ring-3`) 및 after 터치 영역이 클리핑될 수 있음

### 옵션 B — `overflow-clip-margin` 활용 (비표준 지원 제한)
브라우저 지원 불안정으로 MVP 단계에서는 부적합.

### 옵션 C — false-positive로 수용 후 Playwright 검사 임계값 조정
현재 `scrollWidth > clientWidth + 5` → `scrollWidth > clientWidth + 15` 로 완화하면 Switch after 확장(~12px)에 의한 false-positive 제거.
Playwright 캡처 스크립트(`/dc-review-frontend/scripts/review-capture.js`) 임계값 수정.

## 권장 방향

**옵션 C 권장**: 시각 클리핑 없음 + bodyOverflow false = 실제 UX 문제 없음. Playwright 검사 임계값을 15px로 올려 Switch 컴포넌트의 터치 영역 확장에 의한 false-positive를 수용.

별도로 Switch after 확장이 실제로 수평 스크롤을 유발하는 다른 레이아웃 컨텍스트가 있는지 점검 필요.

## 다음 단계

- [ ] review-capture.js overflow 임계값 15px로 조정
- [ ] 다른 Switch 사용 페이지(settings, notifications 등)도 동일 패턴인지 확인
- [ ] 필요 시 `overflow-hidden` 적용 후 focus ring 시각 확인
