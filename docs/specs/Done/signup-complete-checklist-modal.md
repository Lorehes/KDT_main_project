---
type: spec
status: Done
created: 2026-06-17
updated: 2026-06-17
---

# 온보딩 체크리스트 모달 전환 Spec (signup-complete-checklist-modal)

> 상태: Draft → Approved → **Done** (2026-06-17, e438705+f579be2 구현 완료)

## 배경 / 목적

`/signup/complete` 체크리스트의 "등록"·"설정" 버튼이 현재 페이지 이탈 링크(`href`)여서
사용자가 온보딩 맥락을 잃는다. 두 항목을 각각 Sheet/Dialog로 전환해 **페이지를 떠나지 않고
인라인 완료 후 체크 표시**까지 확인할 수 있게 한다.

- **페르소나**: A(개인 투자자), C(시니어·입문 투자자) — 가입 직후 이탈률 최소화
- **BM 티어**: Free(3종목 제한 내 포함) — 온보딩 단계이므로 티어 무관

---

## 요구사항

### R1 — 보유 종목 등록: Sheet (2-step)

"등록" 버튼 및 하단 CTA "첫 종목 등록하기 →" 버튼 클릭 시 Sheet 오픈 (`side="bottom"` 모바일 / `side="right"` sm 이상).

**Step 1 — 종목 검색** (`selectedStock === null`):
- 기존 `StockSearchCombobox` 재사용
- 종목 선택 시 Step 2로 전환 (페이지 이동 없음)

**Step 2 — 정보 입력** (`selectedStock !== null`):
- 선택된 종목명(corp_name) + 코드 헤더 표시
- 매수 평균가 * (required, min=1, 원 단위)
- 보유 수량 * (required, min=1, 정수)
- "← 다시 검색" 버튼 → Step 1으로 복귀
- "저장" → `POST /portfolios` 호출
  - 성공: Sheet 닫힘 + `portfolioDone = true` (체크리스트 ✓)
  - 실패: 인라인 에러 메시지 (Free 제한 422, 중복 409, 기타)
- 매수가·수량 `console.log` 절대 금지 (CLAUDE.md §7)

### R2 — 알림 채널 설정: Dialog (간소화)

"설정" 버튼 클릭 시 Dialog 오픈 (기존 `/notifications/settings` 전체 UI 대신 온보딩 핵심만).

**Dialog 내용**:
- **알림 채널**: KAKAO / EMAIL radio (TELEGRAM disabled · Premium 전용)
  - 기존 `useNotificationSettings()`로 현재 설정값 로드
- **발송 빈도**: 즉시 / 하루1회 / 하루2회 / 주1회
- "저장" → `PUT /notifications/settings` 호출
  - 성공: Dialog 닫힘 + `notifDone = true` (체크리스트 ✓)
- 하단 "전체 설정 보기 →" 링크 → `/notifications/settings` (Dialog는 닫힘)
- type_filter · off_hours_allowed는 이 Dialog에 노출 안 함 (전체 설정 페이지에서)

### R3 — 체크리스트 완료 상태 반영

- `portfolioDone`, `notifDone`: `useState<boolean>` (CompletePage 로컬, 리로드 시 초기화 허용 — 온보딩 단계만 사용)
- 완료된 항목은 `ChecklistItem done={true}` → 체크 아이콘 + 버튼 숨김
- "첫 종목 등록하기 →" 하단 CTA: `portfolioDone` 이후 "대시보드로 이동 →" 텍스트 변경 + `href="/dashboard"`

---

## 영향 범위 (조사 결과)

- **영향 레이어**: frontend(`(auth)/signup/complete/`) — BE·DB 변경 없음
- **영향 파일**:
  - `frontend/src/app/(auth)/signup/complete/page.tsx` — Sheet·Dialog 인라인 추가, 상태 관리
- **재사용 (변경 없음)**:
  - `frontend/src/components/ui/sheet.tsx` — `Sheet·SheetContent·SheetHeader·SheetTitle·SheetFooter`
  - `frontend/src/components/ui/dialog.tsx` — `Dialog·DialogContent·DialogHeader·DialogTitle·DialogFooter`
  - `frontend/src/components/domain/StockSearchCombobox.tsx` — Step 1 검색 컴포넌트
  - `frontend/src/lib/api/portfolios.ts` — `useCreatePortfolio`, `CreatePortfolioBody`
  - `frontend/src/lib/api/notifications.ts` — `useNotificationSettings`, `useUpdateNotificationSettings`, `NotifChannel`, `NotifFrequency`
- **DB 변경**: 없음
- **외부 계약**: 없음 (DART/KRX/카카오/LLM 변경 없음)

---

## 관련 패턴 / 과거 사례

- `ProUpsellModal` (`frontend/src/components/domain/ProUpsellModal.tsx`) — Dialog 오픈 패턴 레퍼런스 (open/onOpenChange props)
- `HamburgerDrawer` (`frontend/src/components/layout/HamburgerDrawer.tsx`) — Sheet 오픈 패턴 레퍼런스 (uiStore 연동, `side="right"`)
- `portfolios/new/page.tsx` — 종목 등록 폼 로직(register/validation/에러처리) 동일 패턴 인라인 재현
- `notifications/settings/page.tsx` — 채널·빈도 UI 동일 패턴 Dialog 내 간소화 버전으로 재현

---

## 리스크 / 법적 검토

- **매수가·수량 로그 금지**: Sheet Step 2 onSubmit 내에서 avg_buy_price·quantity `console.log` 절대 금지 (CLAUDE.md §7 금융 개인정보)
- **`(auth)` 라우트 그룹**: `signup/complete`는 AppShell 미포함. Sheet/Dialog는 Portal로 렌더되므로 z-index 충돌 없음 (확인 완료)
- **Free 3종목 제한 422**: Sheet 내에서도 기존 에러 분기 동일하게 처리 필요
- **알림 채널 간소화**: onboarding Dialog는 type_filter·off_hours_allowed 미노출 — 기본값은 서버 현재값 유지 (`useNotificationSettings` 로드값 그대로 전송). 변경하지 않은 필드는 기존 설정값으로 PUT.

---

## 권장 구현 방향

### Sheet/Dialog를 CompletePage 파일 내 인라인 구현

- 별도 컴포넌트 파일 분리 없이 `page.tsx` 단일 파일 내 3개 컴포넌트로 구성:
  - `CompletePage` (메인) — 상태 관리 + UI
  - `PortfolioSheet` (내부 컴포넌트) — Sheet + 2-step 종목 등록 폼
  - `NotifDialog` (내부 컴포넌트) — Dialog + 채널·빈도 설정
- **Why**: 이 Sheet/Dialog는 온보딩에만 쓰이는 1회성 UI. 공유 컴포넌트화 시 불필요한 prop drilling·abstraction 비용 발생. 파일 1개 수정으로 격리.

### 상태 흐름

```
CompletePage
├── portfolioSheetOpen: boolean  ← "등록" 버튼 / CTA 버튼으로 토글
├── selectedStock: StockSearchResult | null  ← null=Step1, set=Step2
├── portfolioDone: boolean  ← 등록 성공 시 true
├── notifDialogOpen: boolean  ← "설정" 버튼으로 토글
└── notifDone: boolean  ← 설정 저장 성공 시 true
```

### Sheet 2-step 전환

```
portfolioSheetOpen=true, selectedStock=null   → Step 1 (StockSearchCombobox)
portfolioSheetOpen=true, selectedStock=Stock  → Step 2 (매수가·수량 입력 폼)
portfolioSheetOpen=false                      → Sheet 닫힘
```

### Sheet 닫힘 시 상태 초기화

Sheet `onOpenChange(false)` 시 `selectedStock = null` 초기화 → 다음 오픈 시 Step 1부터 시작.

### 알림 Dialog 초기값 로드

`useNotificationSettings()`를 CompletePage 레벨에서 호출, Dialog가 열릴 때 현재값으로 초기화.
단, Dialog가 마운트되기 전에 데이터가 준비돼 있도록 CompletePage 최상단에서 훅 호출.

---

## 참고: 영향 없는 파일

- `portfolios/new/page.tsx` — Sheet 성공 후에도 이 페이지는 직접 navigate하지 않음 (Sheet 내 완료)
- `portfolios/page.tsx` — Sheet 닫힘 후 `["portfolios"]` 쿼리 자동 무효화(`useCreatePortfolio` 기존 동작)로 목록 갱신

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

---

## Tech Review (dc-tech-review · 2026-06-17)

### 아키텍처 분해

- **영향 레이어**: frontend 단일 (`(auth)/signup/complete/page.tsx`) — backend·DB·외부 계약 변경 없음
- **신규**: `PortfolioSheet`(내부 컴포넌트), `NotifDialog`(내부 컴포넌트) — 모두 `page.tsx` 내부 정의
- **수정**: `CompletePage`(상태 5개 추가 + 훅 호출), `ChecklistItem`(`done=true` 시 action 숨김 처리)
- **재사용 확인 완료**: `Sheet/SheetContent/...`, `Dialog/DialogContent/...`, `StockSearchCombobox`(`onSelect: (stock: StockSearchResult) => void`), `useCreatePortfolio`(`mutateAsync`+`ApiException` try/catch 패턴), `useNotificationSettings`/`useUpdateNotificationSettings`(`Partial<NotificationSettings>`)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | CompletePage 상태(`portfolioSheetOpen`/`selectedStock`/`portfolioDone`/`notifDialogOpen`/`notifDone`) + `useNotificationSettings()` 최상단 호출 추가 | FE | FE | 하 | - |
| 2 | `PortfolioSheet` 컴포넌트: Step1 `StockSearchCombobox` → Step2 RHF 폼(`avg_buy_price`/`quantity`), `mutateAsync` + `ApiException` 분기(`BUSINESS_RULE_VIOLATION`/`DUPLICATE_RESOURCE`), 성공 시 `portfolioDone=true` + Sheet 닫힘 | FE | FE | 중 | #1 |
| 3 | `NotifDialog` 컴포넌트: 채널(KAKAO/EMAIL radio, TELEGRAM disabled) + 빈도(INSTANT/DAILY_1/DAILY_2/WEEKLY) UI, `useUpdateNotificationSettings` 호출, 성공 시 `notifDone=true` + Dialog 닫힘 | FE | FE | 중 | #1 |
| 4 | `ChecklistItem` action 슬롯 동작 변경: `<Link>` → `<Button onClick={...}>` 으로 Sheet/Dialog 토글, `done=true` 시 action 숨김 (현 구현 유지 가능) | FE | FE | 하 | #2, #3 |
| 5 | 하단 CTA 동적 전환: `portfolioDone === true` 시 "대시보드로 이동 →" + `href="/dashboard"` | FE | FE | 하 | #1 |
| 6 | 접근성 — Sheet/Dialog `aria-labelledby`, focus trap·ESC(Radix 기본), Step1↔Step2 이동 시 포커스 이동 검증, "다시 검색" 버튼 키보드 경로 | FE | FE | 하 | #2 |

### DB / 마이그레이션 영향

- **없음** — DDL 변경, Flyway 마이그레이션 모두 불필요

### 외부 계약 영향

- **없음** — DART/KRX/카카오/LLM 호출 없음
- 기존 BE REST 계약 그대로 사용: `POST /portfolios`, `GET /notifications/settings`, `PUT /notifications/settings`

### 리스크 & 법적 검토

1. **(법적/§7) 매수가·수량 평문 로그 금지** — Spec에서 식별. Step2 onSubmit에서 `console.log(body)` 절대 금지, 에러 시 `e.body.message`만 노출. 기존 `portfolios/new` 패턴 그대로 복제.
2. **(UX) `NotificationSettings.enabled` 필드 미고려 — 신규 식별** — `NotifDialog`는 channel·frequency만 PUT하는데, 사용자의 `enabled`가 `false`라면 `notifDone=true` 체크 표시되지만 **실제 알림은 OFF**. 결정 필요:
   - (a) Dialog 저장 시 `enabled: true` 강제 포함 (온보딩이므로 명시적 활성화)
   - (b) Dialog 상단에 "알림 받기" 토글 추가
   - **권장: (a)** — 온보딩 맥락은 "처음 설정"이므로 활성화 의도가 분명. Spec 본문에 명시 추가 필요.
3. **(UX) 설정 로딩 race** — `useNotificationSettings` 로딩 중에 "설정" 버튼 클릭 가능. 해결: Dialog 내부에서 `isLoading` 동안 form skeleton 표시 + 저장 disabled (전체 설정 페이지 패턴 재사용).
4. **(UX) 새로고침 시 체크 초기화** — Spec에서 "허용"으로 명시. 백엔드 진실(`usePortfolios().data.length > 0`, `useNotificationSettings().data.enabled`)로 초기값 derive하는 옵션도 고려 가능하나, 온보딩 1회성이므로 현행 유지 OK.
5. **(에러처리) Free 422 / 중복 409 메시지 누락 금지** — `portfolios/new/page.tsx:62-68`의 분기 그대로 복제. "요금제 보기" 링크(Pro 업셀)도 동일 노출.
6. **(접근성) Sheet/Dialog 진입 시 초기 포커스** — Step1 검색 input, Dialog 첫 라디오로 자동 포커스. Radix 기본 동작 확인 필요.

### 예상 wave 수

- **1 wave** — 단일 파일 추가 ~180~220 LoC, 충돌 영향 없음. 1 PR 권장.

### Status 전환 제안

리스크 2번(`enabled` 필드 처리)에 대한 사용자 결정 후 `/dc-spec-move signup-complete-checklist-modal Approved` 로 전환 권장.

