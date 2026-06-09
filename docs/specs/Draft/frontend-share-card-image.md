---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# 공유 카드 이미지 생성 Spec (html2canvas · OG 이미지)

> 상태: **Draft** (dc-plan 생성)

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
