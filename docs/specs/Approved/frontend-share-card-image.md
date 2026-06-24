---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-24
---

# 공유 카드 이미지 생성 Spec (html2canvas · OG 이미지)

> 상태: **Approved** (2026-06-24, dc-tech-review 승인)

## 배경 / 목적

`/share` 페이지의 "이미지 저장" 버튼이 현재 alert로만 구현됐다. 실제 이미지 다운로드·SNS 공유 기능을 완성해 바이럴 성장(통합기획서 §12.2)을 지원한다.

- **현황**: CSS 카드 UI + `navigator.share` 텍스트 공유만 구현. 이미지 변환 미구현.
- **목표**: 공유 카드를 PNG 이미지로 내보내고 다운로드·SNS 공유.
- **접근법 A (클라이언트)**: `html2canvas`로 DOM → Canvas → PNG
- **접근법 B (서버)**: Next.js Route Handler + `@vercel/og` / `satori`로 서버사이드 OG 이미지 생성

---

## 요구사항

- [ ] **R1** 이미지 생성 방식 결정 — A(html2canvas) vs B(satori OG)
- [ ] **R2-A** html2canvas 연동 — `pnpm add html2canvas` → 카드 DOM capture → `toBlob()` → 다운로드
- [ ] **R2-B** satori OG 이미지 — `app/api/share/og/route.ts` → JSX → PNG 반환. `GET /api/share/og?userId=...&week=...`
- [ ] **R3** 다운로드 트리거 — `<a download="공시레이더_주간리포트.png">` + `URL.createObjectURL(blob)`
- [ ] **R4** 모바일 공유 — `navigator.share({ files: [imageFile] })` — 이미지 파일 직접 공유 (iOS/Android 지원)
- [ ] **R5** share-summary 데이터 소스 — 현재 로컬 집계를 `GET /users/me/share-summary` 실 API로 교체 (백엔드 신규 엔드포인트 필요)
- [ ] **R6** 카드 디자인 픽셀 퍼펙트 — html2canvas 렌더링 시 CSS 변수·웹폰트 fallback 처리

---

## 영향 범위

- **영향 레이어**: frontend(주) + backend(선택 — `/share-summary` 엔드포인트)
- **DB 변경**: 없음 (share-summary는 기존 portfolios + notifications 집계)
- **외부 계약**: 없음

### 신규/수정 파일

| 파일 | 내용 |
|------|------|
| `frontend/src/app/(app)/share/page.tsx` | html2canvas 연동 + 다운로드 로직 |
| `frontend/src/app/api/share/og/route.ts` (Option B) | satori OG 이미지 Route Handler |
| `frontend/src/lib/api/auth.ts` | `getShareSummary()` 추가 (백엔드 준비 시) |

---

## 관련 패턴 / 과거 사례

- `frontend-full-ui-implementation` (Done) T29 — 공유 카드 CSS UI + `navigator.share` 구현 완료
- Next.js `@vercel/og` — 서버사이드 이미지 생성 공식 라이브러리 (Vercel 배포 시 최적화됨)

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| html2canvas 웹폰트 미렌더 | `canvas.toDataURL()` 전 폰트 로드 대기 필요. `FontFace.load()` 확인 |
| html2canvas CORS 이미지 | 카드에 외부 이미지 없으므로 해당 없음 |
| 공유 카드 투자 권유 오해 | 카드 하단 면책 문구("정보 제공용, 투자 자문 아님") 반드시 포함 (자본시장법 §11.1) |
| `navigator.share` iOS 미지원 | `!navigator.share` 시 다운로드 전용 버튼으로 폴백 |
| share-summary API 없음 | 백엔드 `GET /users/me/share-summary` 추가 전까지 로컬 집계 유지 |

---

## 권장 구현 방향

**접근법 A (html2canvas) 권장** — 서버 인프라 불필요, Vercel 비용 절감.

```typescript
// share/page.tsx
import html2canvas from "html2canvas";

const handleDownload = async () => {
  const card = document.getElementById("share-card");
  const canvas = await html2canvas(card, {
    backgroundColor: null,
    scale: 2,           // 고해상도
    useCORS: true,
  });
  canvas.toBlob((blob) => {
    const url = URL.createObjectURL(blob!);
    const a = document.createElement("a");
    a.href = url; a.download = "공시레이더_주간리포트.png";
    a.click();
    URL.revokeObjectURL(url);
  }, "image/png");
};
```

Pretendard 폰트는 `document.fonts.ready` 이후 캡처해 렌더 보장.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-24)

### 결론 요약 (R1 결정)

**접근법 A 채택, 단 `html2canvas`가 아니라 `html2canvas-pro`로.**

> 결정적 근거: 현재 `share-card` DOM은 Tailwind 4 기본 색상계인 **`oklch()`** + `var(--color-brand-navy)` CSS 변수로 렌더된다 (`globals.css:31~33`, `share/page.tsx:49`). 구버전 `html2canvas`(최신 릴리스 2022)는 `oklch()`/`color()` 파싱을 못 해 해당 영역을 **검정/투명으로 깨뜨린다**. Spec의 R2-A(`pnpm add html2canvas`)를 그대로 따르면 카드가 깨진 채 캡처된다. `oklch`/`lab`/`color()`를 지원하는 활성 포크 **`html2canvas-pro`**가 사실상 유일한 현실적 선택지.
>
> 접근법 B(satori/@vercel/og)는 기각: satori 역시 oklch 미지원 + Tailwind 임의 클래스(`bg-white/7`, gradient) 미지원이라 카드를 satori 호환 JSX로 **재작성**해야 하고, `SentimentBadge` 등 기존 컴포넌트 재사용 불가. 서버 인프라/폰트 임베딩 비용까지 더해 ROI 낮음. (단 OG 메타 썸네일이 별도 필요해지면 그때 재검토)

### 아키텍처 분해

- **영향 레이어**: frontend(주) — `share/page.tsx` 클라이언트 로직만. backend 변경 **이번 wave 없음**.
- **신규**: 다운로드/공유 핸들러(이미지), 폰트 로드 대기 유틸. 의존성 `html2canvas-pro` 1종.
- **수정**: `share/page.tsx`의 placeholder `alert`(101행) → 실제 캡처. 안내 문구(120행) 제거.
- **R5(share-summary API)는 범위 분리**: backend `GET /users/me/share-summary` 엔드포인트는 **미존재**(grep 확인). 현재 카드는 `usePortfolios()` + `useDisclosures()` 클라이언트 집계로 이미 동작 중 → 이미지화에 불필요. R5는 별도 Spec/wave로 분리 권장(이미지 기능과 결합하지 말 것).

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `pnpm add html2canvas-pro` + 타입 확인 (oklch 지원 포크) | frontend | FE | 하 | - |
| 2 | `document.fonts.ready` 대기 후 캡처하는 다운로드 핸들러 (`scale:2`, `toBlob`→`createObjectURL`→`<a download>`→`revokeObjectURL`) | frontend/share | FE | 중 | #1 |
| 3 | placeholder `alert`/안내문구 제거, "이미지 저장" 버튼 배선 + 캡처 중 로딩/disabled 상태 | frontend/share | FE | 하 | #2 |
| 4 | 모바일 파일 공유 — `navigator.canShare({files})` 가드 후 `navigator.share({files:[File]})`, 미지원 시 다운로드 폴백 (R4) | frontend/share | FE | 중 | #2 |
| 5 | 캡처 검증 — oklch 색/Pretendard 폰트/`SentimentBadge` 아이콘이 PNG에 정상 렌더되는지 Playwright + 육안 확인 | frontend | FE | 중 | #2,#4 |

### DB / 마이그레이션 영향

- **없음**. Spec "영향 범위"와 일치(DB 변경 없음). Flyway 마이그레이션 불필요.

### 외부 계약 영향

- **없음**. DART/KRX/카카오/LLM 무관. R5 백엔드 엔드포인트는 본 wave에서 제외.

### 리스크 & 법적 검토

- **(기술 P0) oklch 파싱 실패** — Spec R2-A의 plain `html2canvas`로는 카드가 깨짐. 반드시 `html2canvas-pro` 사용. → 카드 #1에 반영.
- **(기술 중) 웹폰트 미렌더** — Pretendard 미로드 상태 캡처 시 fallback 폰트로 찍힘. `document.fonts.ready` await 필수(Spec R6과 일치). → 카드 #2.
- **(기술 중) `navigator.share({files})` 호환성** — iOS Safari/일부 안드로이드만 파일 공유 지원. `navigator.canShare({files})`로 사전 가드, 미지원 시 다운로드 폴백. 빈 alert 분기 금지.
- **(법적) 자본시장법** — 카드 하단 면책 문구가 캡처 이미지에 **포함**돼야 함. 현재 DOM 94행에 이미 존재(`투자 자문이 아닙니다`) → 캡처 영역(`#share-card`) 내부이므로 자동 포함됨. 캡처 영역을 면책 문구보다 위로 자르지 말 것. (통합기획서 §11.1)
- **(접근성)** 캡처 중 버튼 `aria-busy`/disabled, 완료 토스트(`sonner` 사용 중)로 다운로드 알림. `alert()` 제거.

### 예상 wave 수

- **1 wave** (카드 #1~#5, frontend 단독). R5 share-summary 실 API는 **별도 후속 wave/Spec**으로 분리.

