---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-22
---

# FE 접근성 · Skeleton · UI 완성도 Spec

> 상태: Draft → **Approved** (2026-06-22, dc-tech-review 승인)

## 배경 / 목적

`frontend-full-ui-implementation`(Done) 으로 7 Zone 26 화면이 완성됐으나, **WCAG 2.1 AA 위반 3건 + Skeleton UI 미적용 + TopBar 검색창 미구현** 이 확인됐다. 본 Spec 은 접근성과 로딩 UX 를 마무리해 시니어/입문 투자자 페르소나(C/E) 까지 포용한다.

- **현황**: `Sidebar.tsx` 스킵 네비 없음, `TierGate.tsx` 잠금 아이콘 `aria-label` 없음, 커스텀 체크박스 스크린리더 미지원, 모든 로딩 상태가 "불러오는 중..." 텍스트, `TopBar` 검색창 input 만 있고 동작 없음
- **목표**: WCAG 2.1 AA 통과 + 모든 데이터 페치 화면에 Skeleton 적용 + 미완성 UI 정리
- **BM 연관**: 페르소나 C(시니어) / E(입문 투자자) — CLAUDE.md §6-5 접근성 요구사항

---

## 요구사항

### 접근성 (WCAG 2.1 AA)

- [ ] **R1** `app/(app)/layout.tsx` 또는 `AppShell.tsx` 에 스킵 네비게이션 링크 추가 — `<a href="#main-content" className="sr-only focus:not-sr-only">본문으로 건너뛰기</a>`. 키보드 사용자가 사이드바 전체 탭 통과 면제
- [ ] **R2** `components/domain/TierGate.tsx` 에 `role="region"` + `aria-label="${planName} 전용 기능 (잠김)"` 추가. 잠금 아이콘 `aria-hidden=true` 유지하되 컨테이너에 의미 부여
- [ ] **R3** `signup/terms/page.tsx` 커스텀 체크박스 컴포넌트 교체 — `<button role="checkbox" aria-checked={checked} aria-labelledby={labelId}>` 또는 shadcn/ui `<Checkbox>` 적용
- [ ] **R4** 모든 인터랙티브 요소에 `:focus-visible` 스타일 검증 — Tailwind `focus-visible:ring-2 focus-visible:ring-primary` 일괄 적용
- [ ] **R5** 호재/악재 배지 색상 대비 4.5:1 검증 — `SentimentBadge.tsx` 색상 토큰이 WCAG AA 기준 통과하는지 axe-core 자동 검사

### Skeleton UI

- [ ] **R6** `components/ui/Skeleton.tsx` (신규) — shadcn/ui Skeleton 패턴 또는 자체 구현. `<Skeleton className="h-4 w-3/4" />` 형태
- [ ] **R7** 로딩 상태 교체 (텍스트 → Skeleton):
  - `app/(app)/portfolios/page.tsx` 포트폴리오 리스트
  - `app/(app)/disclosures/page.tsx` 공시 피드
  - `app/(app)/disclosures/[id]/page.tsx` 공시 상세 + 분석
  - `app/(app)/notifications/page.tsx` 알림 센터
  - `app/(app)/dashboard/page.tsx` 대시보드 카드
- [ ] **R8** Skeleton 표시 임계치 — 200ms 이내 로딩 완료 시 Skeleton 미표시(깜박임 방지). 200ms 초과 시 표시

### UI 미완성 정리

- [ ] **R9** `components/layout/TopBar.tsx` 검색 input 결정 — 구현(공시/종목 검색 라우팅) 또는 제거. 구현 시 `useDebounce(300ms)` + `/disclosures?q=` 라우팅
- [ ] **R10** `app/(app)/portfolios/page.tsx:36` `window.confirm()` → shadcn/ui `<AlertDialog>` 교체 ([[architecture-refactoring-cleanup]] 와 중복 — 본 Spec 에서 시각적 우선순위로 진행)

---

## 영향 범위

- **영향 레이어**: frontend 전용 (`app/(app)/*`, `components/layout`, `components/domain`, `components/ui`)
- **DB 변경**: 없음
- **외부 계약**: 없음

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `frontend/src/components/ui/Skeleton.tsx` (신규) | R6 |
| `frontend/src/components/layout/AppShell.tsx` | R1 스킵 네비 |
| `frontend/src/components/domain/TierGate.tsx` | R2 aria-label |
| `frontend/src/app/(auth)/signup/terms/page.tsx` | R3 체크박스 교체 |
| `frontend/src/app/globals.css` | R4 focus-visible 토큰 |
| `frontend/src/components/domain/SentimentBadge.tsx` | R5 색상 대비 검증 |
| `frontend/src/app/(app)/portfolios/page.tsx` | R7·R10 |
| `frontend/src/app/(app)/disclosures/page.tsx` | R7 |
| `frontend/src/app/(app)/disclosures/[id]/page.tsx` | R7 |
| `frontend/src/app/(app)/notifications/page.tsx` | R7 |
| `frontend/src/app/(app)/dashboard/page.tsx` | R7 |
| `frontend/src/components/layout/TopBar.tsx` | R9 검색 결정 |

---

## 관련 패턴 / 과거 사례

- `frontend-full-ui-implementation` (Done) — `confidence-meter`, `disclaimer-notice` 등 도메인 컴포넌트 — 동일 위치에 Skeleton 추가
- CLAUDE.md §6-5 — 접근성 요구사항 (대비 4.5:1, aria-label, focus-visible, 키보드 경로)
- 통합기획서 §11.1 — 시니어 페르소나 C 배려 (큰 글자 옵션)
- shadcn/ui `Skeleton`, `AlertDialog`, `Checkbox` 공식 패턴

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| Skeleton 도입으로 번들 사이즈 증가 | 자체 Skeleton 컴포넌트 ~200B — 무시 가능 |
| TopBar 검색 구현 결정 시 BE 신규 엔드포인트 필요 | 단기: 기존 `GET /disclosures?q=` 파라미터 활용. BE 변경 없음 |
| AlertDialog 교체로 모바일 UX 변화 | 기존 confirm() 보다 일관된 디자인 — 긍정적 변화 |
| axe-core CI 통합 필요 | Playwright + `@axe-core/playwright` 추가 — 본 Spec 머지 후 별도 PR |
| 색상 대비 미달 시 디자인 토큰 전면 재검토 | 통합기획서 §11.1 페르소나 C 우선 — 토큰 수정 권한 디자인 리뷰 후 |

---

## 권장 구현 방향

- Wave 1 (A11y 기본): R1·R2·R3·R4 — 키보드/스크린리더 즉시 개선
- Wave 2 (Skeleton): R6·R7·R8 — 한 패턴 만들고 일괄 교체
- Wave 3 (UI 정리): R9·R10 — 미완성 UI 제거 또는 완성
- Wave 4 (회귀): axe-core Playwright 추가 (별도 PR)
- [[architecture-refactoring-cleanup]] R10 (`AlertDialog`) 과 중복 — 본 Spec 이 시각/접근성 측면, refactoring 이 코드 측면

## Tech Review (dc-tech-review · 2026-06-22)

### 아키텍처 분해

- **영향 레이어**: frontend 전용 — `components/ui/`, `components/layout/`, `components/domain/`, `app/(app)/*`, `app/(auth)/signup/terms/`
- **DB / 외부 계약 변경**: 없음
- **신규**: `Skeleton.tsx` (ui), `useDelayedLoading` hook (선택)
- **수정 대상**: AppShell · TierGate · SentimentBadge · terms/page · 5개 페이지 로딩 블록 · portfolios/page

### 코드 현황 대조 (R 별 실측)

| R | 요구사항 | 실측 현황 | 조치 필요 |
|---|---------|----------|----------|
| R1 | 스킵 네비게이션 링크 | `id="main-content"` 이미 존재 (AppShell:25). **링크 자체 미존재** | 추가 필요 |
| R2 | TierGate `role="region"` + `aria-label` | 컨테이너 div에 role 없음. Button에 `aria-label` ✅ | 외부 div 수정 |
| R3 | 체크박스 `role="checkbox"` | `aria-pressed` 사용 중 (terms:116·127) — 토글 버튼 semantics ≠ 체크박스 semantics | 교체 필요 |
| R4 | `:focus-visible` 전역 보강 | globals.css `--ring` 토큰 정의 ✅, 개별 요소 `focus-visible:ring-2` 적용 ✅. 누락 요소 일괄 확인 필요 | 부분 보강 |
| R5 | SentimentBadge 색상 대비 | `role="img"` + `aria-label` + 텍스트 + 아이콘 3중 표기 ✅. 색상 토큰 의존 → 대비 수치 코드 기준 미확인 | axe-core 실행 후 판단 |
| R6 | `Skeleton.tsx` 신규 | `components/ui/` 에 없음 | 신규 생성 |
| R7 | 5개 페이지 Skeleton 교체 | portfolios:101 · disclosures:138 · disclosures/[id]:34 · notifications:179 · dashboard:62 — 전부 텍스트 | 전부 교체 |
| R8 | 200ms 임계치 | 없음 | hook 추가 or inline state |
| R9 | TopBar 검색 결정 | **19차 세션(2026-06-22)에서 이미 제거** + `docs/issues/topbar-global-search.md` 이슈화 완료 | **Skip** |
| R10 | `window.confirm` → AlertDialog | portfolios:45 `confirm()` 확인 · `alert-dialog.tsx` ui에 없음, `dialog.tsx` 존재 | shadcn AlertDialog 추가 or Dialog 재활용 |

### 작업 카드

| # | 작업 | 파일 | 난이도 | 의존성 |
|---|------|------|--------|--------|
| W1-1 | AppShell 스킵 네비 링크 추가 (`sr-only focus:not-sr-only`) | `AppShell.tsx` | 하 | - |
| W1-2 | TierGate 컨테이너 `role="region"` + `aria-label` 추가 | `TierGate.tsx` | 하 | - |
| W1-3 | terms/page 개별 체크박스 `aria-pressed` → `role="checkbox" aria-checked` 교체 | `terms/page.tsx` | 하 | - |
| W1-4 | globals.css `*:focus-visible` 전역 fallback 추가 + 누락 요소 확인 | `globals.css` | 하 | - |
| W2-1 | `Skeleton.tsx` 신규 생성 (shadcn 패턴 `animate-pulse bg-muted rounded-md`) | `components/ui/Skeleton.tsx` | 하 | - |
| W2-2 | `useDelayedLoading(isLoading, 200)` hook 신규 (200ms 후 true 반환) | `lib/hooks/useDelayedLoading.ts` | 하 | W2-1 |
| W2-3 | portfolios/page 로딩 텍스트 → 카드 Skeleton 3줄 (종목 카드 3개 높이) | `portfolios/page.tsx` | 하 | W2-1·W2-2 |
| W2-4 | disclosures/page 로딩 텍스트 → 피드 카드 Skeleton 5개 | `disclosures/page.tsx` | 하 | W2-1·W2-2 |
| W2-5 | disclosures/[id]/page 로딩 텍스트 → 상세 + 분석 블록 Skeleton | `disclosures/[id]/page.tsx` | 하 | W2-1·W2-2 |
| W2-6 | notifications/page 로딩 텍스트 → 알림 행 Skeleton 5개 | `notifications/page.tsx` | 하 | W2-1·W2-2 |
| W2-7 | dashboard/page 로딩 텍스트 → 대시보드 카드 Skeleton | `dashboard/page.tsx` | 하 | W2-1·W2-2 |
| W3-1 | portfolios/page `confirm()` → shadcn `AlertDialog` 교체 (shadcn add alert-dialog) | `portfolios/page.tsx` | 중 | - |

### DB / 마이그레이션 영향

없음.

### 외부 계약 영향

없음. (DART/KRX/카카오/LLM 무관)

### 리스크 & 법적 검토

| 항목 | 내용 |
|------|------|
| R9 중복 작업 주의 | TopBar 검색 제거는 19차 세션 완료. Spec R9는 구현 없이 "완료" 처리 |
| AlertDialog 패키지 추가 | `@radix-ui/react-alert-dialog` 신규 dep. 번들 영향 미미 (~3 kB gzipped) |
| 200ms hook 부작용 | `useDelayedLoading`은 마운트 직후 타이머 시작 — `isLoading=false` 즉시 완료 시 Skeleton 미노출. 의도된 동작 |
| SentimentBadge 색상 | 코드 구조 이미 AA 준수 설계. 실제 토큰 hex 값 검증은 axe-core Playwright 실행 시 확인. 토큰 미달 시 `globals.css` 토큰만 수정 (컴포넌트 코드 변경 불필요) |
| 법적 제약 | A11y 전용 작업 — 자본시장법·개인정보 영향 없음 |

### 예상 Wave 수

- **Wave 1** — A11y 기본 (W1-1~W1-4): R1·R2·R3·R4 — 단순 attribute 추가. 1 PR
- **Wave 2** — Skeleton (W2-1~W2-7): R5~R8 — 컴포넌트 1개 + hook 1개 + 5개 페이지 교체. 1 PR
- **Wave 3** — UI 정리 (W3-1): R10 AlertDialog — 패키지 추가 + UI 교체. 1 PR
- **Total**: 3 waves. R9 Skip (기완료). 전체 난이도 **하** (DB·BE 변경 없음, 코드 패턴 반복적)
