---
type: spec
status: Approved
created: 2026-06-16
updated: 2026-06-23
---

# 대시보드 실데이터 연동 Spec (dashboard-real-data)

> 상태: Draft → **Approved** (2026-06-23, dc-tech-review 승인)

## 배경 / 목적

`/dashboard`는 이미 `useDisclosures({ scope: "portfolio" })`와 `usePortfolios()`로
**실 API에 연결된 상태**다. "W4에서 실제 데이터로 교체 예정"이라는 코드 주석은 outdated.
그러나 **Free 5건/일 제한 미구현**, **staleTime 미설정**, **Skeleton UI 없음** 3건의 잔여 갭이 있다.

- **페르소나**: A~E 전체 — 대시보드는 모든 사용자의 앱 홈
- **BM 티어**: Free(하루 5건 제한), Pro(무제한), Premium(무제한)

### 현황 (코드 기반 확인)

| 항목 | 상태 |
|------|------|
| `useDisclosures({ scope: "portfolio" })` 연결 | ✅ 완료 |
| 보유 종목 기반 필터링 | ✅ BE `DisclosureQueryService.resolveStockCodes()` 처리 |
| 포트폴리오 0건 Empty state | ✅ `!hasPortfolios` 분기 구현 |
| 오늘 공시/호재/악재/보유종목 통계 카드 | ✅ 실 데이터 집계 (`disclosures.length` 등) |
| 공시 없을 때 안내 | ✅ "오늘 등록 종목의 신규 공시가 없습니다" |
| `staleTime` 설정 | ❌ 미설정 — 포커스 복귀마다 재요청 |
| Free 5건/일 제한 | ❌ BE `DisclosureQueryService`에 미구현 |
| Skeleton UI | ❌ 텍스트 "공시를 불러오는 중..."만 있음 |
| 오늘 공시 필터 | ❌ 날짜 범위 파라미터 미전달 — 전체 기간 공시 반환 |

---

## 요구사항

### R1 — `staleTime` 설정 (FE)

`useDisclosures` 훅 호출부(`dashboard/page.tsx`)에 `staleTime: 60_000` 추가.
[[performance-caching-staletime]] Spec과 동일 전략 — 공시 피드는 1분 캐시로 포커스 복귀 재요청 억제.

```ts
const { data: disclosurePage, isLoading } = useDisclosures(
  { scope: "portfolio", size: 10 },
  { staleTime: 60_000 }
);
```

### R2 — 오늘 공시만 표시 (FE)

현재 날짜 범위 파라미터를 전달하지 않아 전체 기간 공시가 내려옴.
대시보드는 "오늘의 내 공시 레이더"이므로 오늘 날짜 필터 적용:

```ts
const today = new Date().toISOString().slice(0, 10); // "YYYY-MM-DD"
useDisclosures({ scope: "portfolio", size: 10, from: today, to: today })
```

BE `DisclosureQueryService.list()`의 `fromDate`/`toDate` 파라미터가 이미 지원됨.

### R3 — Free 5건/일 제한 (BE)

`DisclosureQueryService.list()`에서 `tier == FREE`이면 오늘 날짜 범위 조회 결과를 최대 5건으로 제한.
초과분은 잘라내고 응답 메타에 `free_limit_reached: true` 플래그 추가 (또는 클라이언트가 size로 추론).

> 구현 방향: `size` 파라미터를 Free 티어에서 `Math.min(size, 5)`로 클램핑 후 처리.
> 별도 엔드포인트 없이 기존 `list()` 메서드 내 분기로 처리.

### R4 — FE Free 제한 안내 (FE)

`disclosurePage.page.total_elements > 5 && tier == FREE` 조건 시
"오늘 5건 조회 완료 — Pro로 업그레이드하면 전체 공시를 확인할 수 있어요" 배너 + `TierGate` 표시.

### R5 — Skeleton UI (FE)

`isLoading` 상태에서 텍스트 대신 `DisclosureCard` 형태의 Skeleton 표시.
shadcn/ui `Skeleton` 컴포넌트(`<div className="h-[72px] animate-pulse rounded-2xl bg-muted" />`) 4개 렌더링.
[[fe-accessibility-skeleton-ui]] Spec과 동일 패턴.

---

## 영향 범위

- **영향 레이어**: frontend(`dashboard/`, `lib/api/disclosures.ts`) + backend(`disclosure/services/`)
- **영향 파일**:
  - `frontend/src/app/(app)/dashboard/page.tsx` — R1(staleTime), R2(today 필터), R4(제한 안내), R5(Skeleton)
  - `backend/.../disclosure/services/DisclosureQueryService.java` — R3(Free 5건 클램핑)
  - `backend/.../disclosure/dto/DisclosureListItemResponse.java` — R3 플래그 필요 시
- **DB 변경**: 없음
- **외부 계약**: 없음

---

## 관련 패턴 / 과거 사례

- [[disclosure-collection-pipeline]] Done — 공시 수집 파이프라인 (데이터 소스)
- [[analysis-stage2-llm]] Done — sentiment/confidence 분析 데이터 (DisclosureCard 표시 필드)
- [[portfolio-management-e2e]] Draft — 선행 의존: 포트폴리오가 등록돼야 대시보드에 공시 표시
- [[performance-caching-staletime]] Draft — staleTime 전략 (병행 적용)
- [[fe-accessibility-skeleton-ui]] Draft — Skeleton UI 패턴
- 기존 구현: `backend/.../disclosure/services/DisclosureQueryService.java` 전체 (scope/date 필터 확인)

## 리스크 / 법적 검토

- LLM 분析 결과(`summary`, `sentiment`) 노출 시 면책 문구 필수 — `DisclosureCard`에 이미 포함됨 확인 필요
- Free 5건 제한은 클라이언트 조작 가능 — BE에서 강제(`size` 클램핑)가 필수
- "투자 권유" 표현 금지 — Free 제한 안내 문구에 "수익 보장" 등 표현 사용 금지 (자본시장법 §11.1)

## 권장 구현 방향

R2(오늘 필터) → R1(staleTime) → R5(Skeleton) → R3·R4(Free 제한) 순서.
R3는 BE 변경이 필요하므로 FE 선작업 후 BE 적용.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-23)

### ⚠️ 전제 재검증 — Spec 작성(2026-06-16) 이후 후속 Spec 2건 Done 반영

본 Spec은 [[performance-caching-staletime]]·[[fe-accessibility-skeleton-ui]](둘 다 2026-06-23 Done) **이전**에 작성되어 R1·R5가 이미 해소됐다. 코드 실측 결과:

| R | Spec 원전제 | 현재 코드(2026-06-23) | 판정 |
|---|------------|----------------------|------|
| **R1** staleTime | `dashboard/page.tsx`에 `staleTime: 60_000` 추가 필요 | `lib/api/disclosures.ts:98` `useDisclosures`가 **훅 레벨에서 `staleTime: 60_000` 적용**(performance-caching-staletime) | ✅ **완료** — 추가 작업 없음 |
| **R2** 오늘 필터 | from/to 미전달 → 전체 기간 반환 | `dashboard/page.tsx:24` `{ scope:"portfolio", size:10 }` — from/to **여전히 미전달** | ❌ **유효** |
| **R3** Free 5건 | `DisclosureQueryService`에 미구현 | `size = Math.min(size,100)` 이중방어 + `scope=all` FREE 403만 존재. **일 5건 클램핑 없음** | ❌ **유효** |
| **R4** FE 제한 안내 | 미구현 | 미구현 | ❌ **유효**(R3 의존) |
| **R5** Skeleton | 텍스트 "불러오는 중"만 존재 | `dashboard/page.tsx:63-78` **`useDelayedLoading` + `Skeleton` 5개 구현**(fe-accessibility-skeleton-ui) | ✅ **완료** — 추가 작업 없음 |

> **추가 발견 — outdated 주석**: `dashboard/page.tsx:6-7,46-47` 머리 주석이 "W4에서 실제 데이터로 교체 예정"으로 남아 있으나, 통계 카드·공시 피드는 이미 실 데이터 집계(`disclosures.length`, `usePortfolios()`). 정정 필요.

### Free 제한 메커니즘 결정 (사용자 승인 2026-06-23)

통합기획서 §8.1: **Free = 종목 3개, 일 5건 공시, 카카오 알림톡**. R3의 "일 5건"은 BM 정책에 부합.

- **채택: 오늘+page0+5건 강제** — `tier == FREE`이면 BE `DisclosureQueryService.list()`에서 ① `fromDate/toDate`를 **서버 기준 오늘(Asia/Seoul)로 강제**, ② `page=0` 강제, ③ `size=Math.min(size,5)`.
- **반려**: Spec 원안 `size` 클램핑만 — `page=1,2…` 페이지네이션으로 6건+ 우회 가능(BM 누수). 대시보드(page=0 고정)엔 동작하나 `/disclosures` 피드에서 무력화.
- 정당화: "일 5건"의 자연스러운 해석이자 페이지네이션 우회 차단. 대시보드 UX("오늘의 공시 레이더")와 일치.

### 아키텍처 분해

- **영향 레이어**: frontend(`dashboard/`) + backend(`disclosure/services/`)
- **신규**: 없음
- **수정**: `DisclosureQueryService.list()`(Free 강제 분기), `dashboard/page.tsx`(R2 from/to, R4 안내 배너, 주석 정정)
- **비변경 확정**: `disclosures.ts`(R1 완료), Skeleton 블록(R5 완료), `DisclosureListItemResponse`(별도 플래그 불필요 — 아래 R4 참조)

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| **Wave 1 — FE 오늘 필터 + 주석 정정** | | | | | |
| 1 | `dashboard/page.tsx` — `useDisclosures`에 `from/to = 오늘(YYYY-MM-DD)` 추가(R2) + 머리 주석 outdated("W4 교체 예정") 정정 | frontend/dashboard | FE | 하 | - |
| **Wave 2 — BE Free 강제 + FE 안내** | | | | | |
| 2 | `DisclosureQueryService.list()` — `tier==FREE` 분기: `fromDate/toDate=LocalDate.now(Asia/Seoul)` 강제, `page=0` 강제, `size=Math.min(size,5)`(R3). scope=portfolio 경로에 적용(scope=all은 기존 403 유지) | backend/disclosure | BE | 중 | - |
| 3 | `dashboard/page.tsx` — `page.total_elements > 5 && tier==FREE` 시 "오늘 5건 조회 완료 — Pro 업그레이드 시 전체 확인" 안내 배너 + 기존 `TierGate`/`ProUpsellModal` 패턴 재사용(R4) | frontend/dashboard | FE | 하 | #2 |

> **R4 플래그 불필요 확정**: 카드 #2가 `page=0/size=5`로 잘라도 `PageResponse.total_elements`는 오늘 범위 전체 카운트를 반환하므로, FE가 `total_elements > 5`로 제한 도달을 추론 가능. `DisclosureListItemResponse`에 `free_limit_reached` 신규 필드 추가 불필요(Spec 원안의 "또는 클라이언트가 size로 추론" 경로 채택).

### DB / 마이그레이션 영향

- **없음.** 컬럼·인덱스·DTO 스키마 변경 없음. R3은 순수 쿼리 파라미터 분기.

### 외부 계약 영향

- **없음.** DART/KRX/카카오/LLM 무관. 자체 REST `GET /disclosures` 응답 스키마 무변경(기존 PageResponse 그대로).

### 리스크 & 법적 검토

- **타임존 일관성(중)**: 카드 #2의 "오늘"은 반드시 `LocalDate.now(ZoneId.of("Asia/Seoul"))`로 산출. 서버 UTC 기본값 사용 시 자정 전후 9시간 오차로 Free 사용자가 "어제" 공시를 보거나 누락. FE R2의 `today`(브라우저 로컬)와도 미세 불일치 가능 — **BE가 tier==FREE일 때 항상 오늘 강제**하므로 BE 기준이 SSOT(FE 전달값 무시).
- **자본시장법 §11.1(중)**: R4 Free 제한 안내 문구에 "수익 보장"·"매수 추천" 등 투자 권유 표현 금지. "Pro 업그레이드 시 전체 공시 확인" 수준의 기능 안내로 한정.
- **LLM 면책(하)**: 대시보드 `DisclosureCard`가 `summary`/`sentiment` 노출 — 면책 문구 동반 여부는 `DisclosureCard` 컴포넌트에서 이미 처리되는지 구현 시 확인(Spec 리스크 항목 유지).
- **BM 누수 차단**: 채택안이 페이지네이션 우회를 막아 Free→Pro 전환 유인 보존.

### 예상 wave 수

- **2 wave**(원안 유지, R1·R5 완료로 카드 5→3 축소):
  - **Wave 1** (R2 + 주석): 카드 #1. FE 독립, 1 PR.
  - **Wave 2** (R3 + R4): 카드 #2(BE) → #3(FE, #2 의존). 1 PR. BE 선행 후 FE 안내.

### 확인 필요 (구현 시점)

1. **카드 #3** — `DisclosureCard`에 LLM 분석 면책 문구가 이미 포함됐는지 구현 직전 확인(통합기획서 §11.1). 미포함 시 별도 후속.
