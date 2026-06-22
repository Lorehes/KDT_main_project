---
type: spec
status: Draft
created: 2026-06-22
updated: 2026-06-22
---

# dc-review-frontend 호버 상태 캡처 Spec

> 상태: **Draft** (dc-plan 생성)
> 발견: `pricing-nav-auth-consistency` dc-review-frontend Info 이슈
> 관련: [[pricing-nav-auth-consistency]]

## 배경 / 목적

`dc-review-frontend` Playwright 캡처 스크립트(`review-capture.js`)는 현재 정적 full-page 스크린샷만 생성한다.
`checklist.md` PC #8 "호버/인터랙션 — 호버 상태가 자연스러운가" 항목이 평가 기준에 정의되어 있으나,
정적 스크린샷으로는 hover 시각을 확인할 수 없어 매 리뷰마다 "미확인" 처리된다.

TailwindCSS `hover:` 유틸리티로 정의된 색상 전환(네비 링크 `hover:text-foreground`),
버튼 배경 변화(`hover:bg-primary/90`), 카드 그림자 변화 등은 UI 품질의 핵심 피드백 요소이므로
자동 캡처가 필요하다.

- **적용 대상**: `dc-review-frontend` 스킬 전체 (페이지 무관 범용)
- **BM 관련 없음**: 개발 도구 개선 (코드/디자인 품질 파이프라인)

## 요구사항

- [ ] `review-capture.js` 실행 시 PC·Mobile 각 뷰포트에서 hover 상태 스크린샷 추가 생성
- [ ] hover 대상: 뷰포트 내 visible 인터랙티브 요소(`button`, `a[href]`, `[role="button"]`) 면적 기준 상위 6개
- [ ] 각 hover 스크린샷은 요소 bbox + 20px padding crop으로 저장 (`{name}-hover-{index}.png`)
- [ ] hover 캡처 결과(대상 요소 목록, 셀렉터)를 기존 JSON 리포트(`{name}-report.json`)에 `hoverCaptures` 배열로 추가
- [ ] bbox null(display:none 등) 요소는 건너뜀 — null guard 필수
- [ ] 스크롤 밖 요소는 `scrollIntoViewIfNeeded` 선행
- [ ] `checklist.md` PC #8 기준 보완 — "정적 스크린샷 미확인" 대신 "hover 캡처 이미지 기반 평가" 명시
- [ ] SKILL.md Phase 1 캡처 목록에 hover 캡처 추가

## 영향 범위 (조사 결과)

- 영향 레이어: **skill 스크립트 only** (프론트엔드 코드·BE·DB 무관)
- 영향 파일:
  - `.claude/skills/dc-review-frontend/scripts/review-capture.js` — **핵심 수정 대상**
  - `.claude/skills/dc-review-frontend/references/checklist.md` — PC #8 기준 텍스트 보완
  - `.claude/skills/dc-review-frontend/SKILL.md` — Phase 1 캡처 목록 업데이트
- DB 변경: **없음**
- 외부 계약: **없음**

## 관련 패턴 / 과거 사례

- 기존 인터랙션 캡처 패턴: `review-capture.js:88~95` — 모바일 메뉴 toggle 후 스크린샷
  (`menuToggle.click()` → `waitForTimeout(500)` → `screenshot()`)과 동일 패턴
- Playwright hover: `page.hover(selector)` 또는 `element.hover()` → CSS `:hover` + JS `mouseenter` 트리거
- 요소 crop: `element.boundingBox()` + `page.screenshot({ clip: {...} })`
- 과거 해결 사례: `docs/solutions` 디렉터리 미존재 — 해당 없음

## 리스크 / 법적 검토

- **자본시장법/개인정보**: 해당 없음 (개발 도구, 데이터 비관여)
- **스크립트 실행 시간 증가**: PC + Mobile 각 6개 = 최대 12회 추가 hover+캡처 → 페이지당 ~5~10초 추가 예상. `waitForTimeout`을 200ms로 최소화해 완화.
- **요소 감지 오탐**: 크기 기준 자동 선택이라 실제 중요한 hover 대상을 놓칠 수 있음 → 향후 `--hover-selectors` CLI 옵션으로 수동 지정 확장 가능

## 권장 구현 방향

### 핵심 로직 (review-capture.js에 추가)

```js
async function captureHoverStates(page, outputDir, viewportName) {
  // viewport 내 visible 인터랙티브 요소 상위 6개 선택 (면적 기준)
  const targets = await page.evaluate(() => {
    const els = Array.from(document.querySelectorAll('button, a[href], [role="button"]'));
    return els
      .map((el, i) => {
        const r = el.getBoundingClientRect();
        return {
          index: i,
          selector: el.tagName + (el.className ? '.' + el.className.trim().split(' ')[0] : ''),
          area: r.width * r.height,
          inView: r.top >= 0 && r.bottom <= window.innerHeight && r.width > 0,
          text: (el.textContent || '').trim().substring(0, 30),
        };
      })
      .filter(t => t.inView && t.area > 400)
      .sort((a, b) => b.area - a.area)
      .slice(0, 6);
  });

  const captures = [];
  for (const [i, target] of targets.entries()) {
    try {
      const el = (await page.$$('button, a[href], [role="button"]'))[target.index];
      if (!el) continue;
      await el.scrollIntoViewIfNeeded();
      await el.hover();
      await page.waitForTimeout(200);
      const box = await el.boundingBox();
      if (!box) continue;
      const clip = {
        x: Math.max(0, box.x - 20),
        y: Math.max(0, box.y - 20),
        width: box.width + 40,
        height: box.height + 40,
      };
      const filename = `${viewportName}-hover-${i}.png`;
      await page.screenshot({ path: `${outputDir}/${filename}`, clip });
      captures.push({ index: i, filename, text: target.text, selector: target.selector });
    } catch (e) {
      // 요소 사라짐·transition 실패 무시
    }
  }
  return captures;
}
```

JSON 리포트 `hoverCaptures` 필드:
```json
{
  "hoverCaptures": [
    { "index": 0, "filename": "pc-hover-0.png", "text": "무료로 시작", "selector": "A.inline-flex" },
    ...
  ]
}
```

### checklist.md PC #8 개정 (기준 명확화)

```md
| 8 | **호버/인터랙션** | `{name}-hover-{n}.png` 캡처 이미지 기반 평가.
색상·배경·그림자 전환이 자연스러운가. 전환 없음(CSS hover 미정의)이면 C. |
```

## Tech Review (dc-tech-review · 2026-06-22)

### 아키텍처 분해
- 영향 레이어: **skill 스크립트 only** — `.claude/skills/dc-review-frontend/` 하위 3파일. FE 코드·BE·DB 무관.
- 신규: `captureHoverStates()` 헬퍼 함수 (review-capture.js 내부)
- 수정: `reviewPage()` 메인 루프(hover 캡처 호출 추가), JSON 리포트 구조(`hoverCaptures` 필드), checklist.md PC #8, SKILL.md Phase 1 목록

### 핵심 보정 — `page.$$()[target.index]` 재선택 타이밍 리스크

Spec 권장 코드의 `page.$$()[target.index]` 방식은 `evaluate()`와 `$$()` 사이 DOM 변경 시 인덱스 어긋남 위험. 구현 시 아래 패턴으로 교체:

```js
// evaluate()에서 임시 data 속성 부여
await page.evaluate((indices) => {
  const els = Array.from(document.querySelectorAll('button, a[href], [role="button"]'));
  indices.forEach(idx => { if (els[idx]) els[idx].dataset.pwHoverIdx = idx; });
}, targets.map(t => t.index));

// data 속성으로 안전 선택
const el = page.locator(`[data-pw-hover-idx="${target.index}"]`);

// 캡처 후 cleanup
await page.evaluate(() => {
  document.querySelectorAll('[data-pw-hover-idx]').forEach(e => delete e.dataset.pwHoverIdx);
});
```

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `captureHoverStates()` 함수 추가 — viewport 내 상위 6개 요소 자동 감지 + data-attr 안전 재선택 패턴 적용 | skill/scripts | FE | 하 | - |
| 2 | `reviewPage()` 루프에 `captureHoverStates()` 호출 통합 — full-page 캡처 직후, JSON 리포트에 `hoverCaptures` 배열 추가 | skill/scripts | FE | 하 | #1 |
| 3 | `references/checklist.md` PC #8 기준 텍스트 개정 — "정적 미확인" → "hover 캡처 이미지 기반 평가, `{name}-hover-{n}.png` 참조" | skill/references | FE | 하 | #2 |
| 4 | `SKILL.md` Phase 1 캡처 목록 업데이트 — hover 캡처 항목 추가, 출력 파일 목록 갱신 | skill | FE | 하 | #3 |

### DB / 마이그레이션 영향
- **없음.** Flyway 마이그레이션 불필요. 스키마 무변경.

### 외부 계약 영향
- **없음.** DART/KRX/카카오/LLM 무관.

### 리스크 & 법적 검토
- **자본시장법/개인정보**: 해당 없음.
- **Playwright `waitForTimeout` 경고**: 최신 Playwright에서 deprecated 예정 — `new Promise(r => setTimeout(r, 200))` 또는 `page.waitForTimeout(200)` (현 버전은 아직 유효) 사용, 향후 API 변경 시 수정 필요.
- **모바일 hover 한계**: `isMobile:true` 컨텍스트에서 `hover()` 동작하나 실제 iOS/Android 터치 기기와 다름 — 스크립트 검증 범위 내에서 허용. 리포트에 "headless 시뮬레이션" 명시 권장.
- **실행 시간 증가**: PC+Mobile 각 6개 = 최대 12회 추가 → 페이지당 ~5~10초. 200ms timeout 최소화로 완화. 시간 민감 시 `--no-hover` 플래그로 skip 옵션 제공 가능.

### 예상 wave 수
- **1 wave** (단일 소규모 스크립트 PR, 스킬 파일 3개 수정)
