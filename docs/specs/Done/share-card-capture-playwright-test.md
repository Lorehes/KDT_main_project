---
type: spec
status: Done
created: 2026-06-24
updated: 2026-06-24
---

# 공유 카드 캡처 Playwright 테스트 Spec

> 상태: **Done** (2026-06-24, 구현 완료 — Playwright 5/5 GREEN)

## 배경 / 목적

`share/page.tsx` 구현 후 `dc-review-code`에서 L4(테스트 미비) 이슈가 제기됐다.
`captureShareCard()` 함수는 브라우저 **Canvas API + oklch CSS 렌더링**이 필요해
Vitest/happy-dom 환경에서는 테스트 불가 — **Playwright e2e 가 유일한 검증 경로**다.

검증해야 할 핵심 질문:
- html2canvas-pro가 oklch 색상·CSS 변수(`--color-brand-navy`)를 올바르게 캡처하는가?
- Pretendard 웹폰트가 PNG에 렌더되는가? (font fallback 여부)
- 다운로드 트리거(`<a download>`)가 실제 파일로 저장되는가?
- `navigator.canShare` 미지원 환경에서 다운로드 폴백이 작동하는가?
- 캡처 중 버튼 `disabled` → 완료 후 복원이 정상 동작하는가?

## 요구사항

- [ ] **T1** 다운로드 플로우 — "이미지 저장" 클릭 → `page.waitForEvent('download')` → filename/size 검증
- [ ] **T2** 파일 내용 검증 — 다운로드된 PNG의 픽셀 샘플링으로 oklch 그라디언트(진청색 계열) 렌더 확인
- [ ] **T3** 폰트 렌더 확인 — 다운로드된 PNG에 텍스트 영역이 블랙박스(#000 또는 transparent)가 아님 확인
- [ ] **T4** 버튼 상태 — 캡처 시작 시 두 버튼 `disabled` 속성 + 텍스트 "처리 중…" 전환, 완료 후 복원
- [ ] **T5** 공유 폴백 — `navigator.share` 미지원 시뮬레이션(page.evaluate로 undefined 치환) → 다운로드로 폴백
- [ ] **T6** AbortError 무시 — `navigator.share` mock에서 AbortError throw 시 에러 토스트 미발화 확인

## 영향 범위

- **영향 레이어**: frontend 테스트 전용
- **신규 파일**: `frontend/e2e/share/capture.spec.ts`
- **DB 변경**: 없음
- **외부 계약**: 없음 — BE는 `page.route()`로 전량 모킹

## 관련 패턴 / 과거 사례

기존 e2e 패턴 (확인된 파일):

| 파일 | 참고 패턴 |
|------|-----------|
| `frontend/e2e/auth/token-refresh.spec.ts` | `page.route()` BE 모킹, `dr_session` 쿠키 픽스처, BrowserContext 활용 |
| `frontend/e2e/portfolios/keyboard-nav.spec.ts` | `makeFakeSessionJwt()` helper, 주요 UI 인터랙션 검증 패턴 |
| `frontend/playwright.config.ts` | `testDir: ./e2e`, Chromium 단일 프로젝트, `webServer: pnpm dev`, `baseURL: localhost:3000` |

공통 패턴:
- BE 호출은 `page.route("http://localhost:8080/api/v1/**", handler)` 전량 모킹
- 세션 쿠키는 `ctx.addCookies([{ name: "dr_session", value: fakeJwt }])`
- dev 서버 자동 기동(`reuseExistingServer: !process.env.CI`)

## 구현 시 핵심 고려사항

### T1 다운로드 캡처
```typescript
// Playwright download 이벤트 캡처
const [download] = await Promise.all([
  page.waitForEvent("download"),
  page.getByRole("button", { name: "카드 이미지 다운로드" }).click(),
]);
expect(download.suggestedFilename()).toBe("공시레이더_주간리포트.png");
const path = await download.path();
const stat = await fs.stat(path!);
expect(stat.size).toBeGreaterThan(10_000); // 빈 PNG 방지
```

### T2 픽셀 샘플링 — oklch 색상 검증
```typescript
// 다운로드 PNG를 Canvas로 로드해 좌상단 픽셀 색상 확인
// html2canvas-pro가 oklch를 깨면 rgb(0,0,0) 또는 rgba(0,0,0,0) 리턴
const { createCanvas, loadImage } = await import("canvas"); // pnpm add -D canvas
const img = await loadImage(path!);
const cv = createCanvas(img.width, img.height);
const ctx = cv.getContext("2d");
ctx.drawImage(img, 0, 0);
const [r, g, b] = ctx.getImageData(10, 10, 1, 1).data;
// 브랜드 navy(--brand-navy ≈ #0d2137) → r<50, g<50, b>50
expect(r).toBeLessThan(50);
expect(b).toBeGreaterThan(50);
```

> `canvas` npm 패키지(node-canvas)가 필요하다 — Node.js 환경에서 PNG 픽셀 분석용.
> `pnpm add -D canvas` + `@types/canvas` 설치 필요. (확인 필요: M1/M2 Mac 빌드 의존성)

### T5 navigator.share 미지원 시뮬레이션
```typescript
// navigator.share를 undefined로 치환
await page.evaluate(() => {
  Object.defineProperty(navigator, "share", { value: undefined, configurable: true });
  Object.defineProperty(navigator, "canShare", { value: undefined, configurable: true });
});
// 이후 "공유하기" 클릭 → 다운로드 폴백
const [download] = await Promise.all([
  page.waitForEvent("download"),
  page.getByRole("button", { name: "카드 이미지 공유" }).click(),
]);
expect(download.suggestedFilename()).toBe("공시레이더_주간리포트.png");
```

## 리스크 / 법적 검토

| 리스크 | 대응 |
|--------|------|
| `canvas` 패키지 M-chip 네이티브 빌드 실패 | `pnpm add -D canvas` 후 빌드 확인 필요. 실패 시 T2는 파일 크기 검증으로 대체 |
| oklch 색상값 환경 차이 | 특정 RGB 값 대신 "navy 계열(r<50, b>50)" 조건 범위 검증으로 완화 |
| html2canvas-pro 캡처 타임아웃 | `timeout: 30_000` (현재 config 20초 기본값보다 넉넉하게 설정) |
| `fonts.ready` 대기 후 캡처라도 CI 환경 폰트 미설치 | CI에서 Pretendard 미설치 시 T3 fallback 텍스트 확인으로 대체. 폰트 없어도 글자가 깨지지는 않음(system fallback) |

## 권장 구현 방향

1. `frontend/e2e/share/` 디렉토리 신설
2. `capture.spec.ts` 1파일에 T1~T6 순차 배치 (공유 상태 없어 순서 독립)
3. `canvas` npm 설치 여부는 구현 시 M-chip 빌드 가능 확인 후 결정 (T2 건너뛰기 가능)
4. 기존 `makeFakeSessionJwt()` helper를 `e2e/helpers/` 공통 모듈로 추출 후 재사용 (현재 keyboard-nav에 로컬)

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-24)

### 아키텍처 분해

- **영향 레이어**: frontend 테스트 전용. backend/DB/외부 API **무관**.
- **신규**: `frontend/e2e/share/capture.spec.ts`, `frontend/e2e/helpers/session.ts`(공통 helper 추출).
- **수정**: `frontend/e2e/portfolios/keyboard-nav.spec.ts`(로컬 `makeFakeSessionJwt` → helper import로 교체, 선택).
- **검증된 전제**(grep 확인):
  - `playwright.config.ts` — `testDir: ./e2e`, chromium 단일, `webServer: pnpm dev`, `baseURL: localhost:3000`, 기본 `timeout: 20_000`.
  - `package.json` 스크립트 `test:e2e: playwright test` 존재 → 별도 배선 불필요.
  - `e2e/helpers/` 디렉토리 **미존재** → T0(helper 추출)이 신규 생성 동반.
  - share 버튼 `aria-label`: `"카드 이미지 다운로드"` / `"카드 이미지 공유"` — Spec의 `getByRole` name과 일치 확인.

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 0 | `e2e/helpers/session.ts` 추출 — `makeFakeSessionJwt`+`grantSession`+BE mock helper를 keyboard-nav/token-refresh에서 공통화 | frontend/e2e | FE | 하 | - |
| 1 | `e2e/share/capture.spec.ts` 스캐폴드 — 세션 쿠키 + portfolios/disclosures `page.route` 모킹 + share 페이지 진입 | frontend/e2e | FE | 중 | #0 |
| 2 | T1·T4 — 다운로드 이벤트 캡처(filename/size) + 버튼 disabled→"처리 중…"→복원 | frontend/e2e | FE | 중 | #1 |
| 3 | T5·T6 — `navigator.share`/`canShare` undefined 치환 다운로드 폴백 + AbortError 토스트 미발화 | frontend/e2e | FE | 중 | #1 |
| 4 | T2·T3 — PNG 픽셀 샘플링(oklch navy 계열·텍스트 비-블랙박스). **`canvas` 빌드 가능 시에만**, 실패 시 size 검증으로 강등 | frontend/e2e | FE | 상 | #2 |

> 카드 4는 `canvas`(node-canvas) M-chip 네이티브 빌드에 의존 — **빌드 실패 시 T2/T3는 카드 2의 파일 크기 검증으로 흡수하고 픽셀 검증은 보류**한다. 카드 0~3만으로도 핵심 회귀(다운로드·폴백·버튼 상태)는 커버됨.

### DB / 마이그레이션 영향

- **없음**. Flyway 마이그레이션 불필요. 스키마(`db_schema.md`) 무관.

### 외부 계약 영향

- **없음**. DART/KRX/카카오/LLM 무관. BE(localhost:8080)는 `page.route()`로 전량 모킹 — 실 BE 불필요(기존 e2e 패턴과 동일).

### 리스크 & 법적 검토

- **(기술 상) `canvas` 네이티브 빌드** — node-canvas는 M-chip에서 `pkg-config`/`cairo` 등 시스템 의존성 필요. 빌드 실패 위험이 가장 큼 → 카드 4를 **선택적**으로 격리, 핵심 카드(0~3)와 분리해 전체 wave가 막히지 않게 설계.
- **(기술 중) oklch 캡처 타임아웃** — html2canvas-pro + `fonts.ready` 대기로 기본 20초를 넘길 수 있음 → spec 파일에 `test.setTimeout(30_000)` 명시.
- **(기술 중) CI 폰트 미설치** — Pretendard 미설치 시 T3는 system fallback으로 텍스트가 깨지지 않으므로 "비-블랙박스" 조건만 검증(특정 폰트 매칭 금지).
- **(개인정보)** 테스트 mock 데이터에 **실제 매수가/보유 종목 평문을 넣지 말 것** — 가공 mock(삼성전자 등 공개 종목)만 사용(CLAUDE.md §7). 캡처 PNG가 trace artifact로 저장되므로 민감정보 포함 금지.

### 예상 wave 수

- **1 wave** (카드 0~3 필수, 카드 4는 `canvas` 빌드 성공 시 동일 wave 포함 / 실패 시 분리·보류). frontend 테스트 단독.
