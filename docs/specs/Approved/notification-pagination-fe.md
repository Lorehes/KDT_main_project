---
type: spec
status: Approved
created: 2026-06-25
updated: 2026-06-25
---

# 알림 목록 페이지네이션 FE Spec

> 상태: **Approved** (2026-06-25, dc-tech-review 승인)
> 선행 Spec: [[be-api-alignment-mvp-r1]] (Done) — BE PageResponse 반환 구현 완료
> 관련 마일스톤: M3 잔여 / MVP 수용 가능(size=20 첫 페이지 제한)

## 배경 / 목적

- **문제**: BE `/api/v1/notifications`는 `PageResponse<NotificationDto>` 반환 (`content[]` + `page` 메타). FE `useNotifications` 훅은 `apiClient<{ content: Notification[] }>` 타입으로 `.content`만 소비 — `totalPages`, `totalElements`, `hasNext` 무시. 결과적으로 첫 페이지(기본 size=20) 이후 알림을 볼 수 없음.
- **현황**: `notifications/page.tsx:52` — `useNotifications()` 파라미터 없이 호출. `data?.content ?? []` 전체 사용. 더 보기/페이저 UI 없음.
- **해결**: `useNotifications` 훅에 `totalPages`·`hasNext` 타입 추가 + 알림 목록 페이지에 **"더 보기" 버튼** 방식 무한 로드 또는 **페이지 네비게이터** 추가.
- **BM 티어**: 전 티어 — 알림은 모든 사용자 기능.
- **페르소나**: A(직장인, 빠른 확인), B(적극적 수집) — 알림 이력 소급 탐색 필요.

## 현황 분석

### FE 타입 불일치

```typescript
// 현재 (notifications.ts:57)
queryFn: () => apiClient<{ content: Notification[] }>(`/notifications${qs}`),

// BE 실제 응답 (PageResponse)
{
  content: Notification[],
  page: { size: number, number: number, totalElements: number, totalPages: number }
}
```

`useNotifications` 반환 타입이 `{ content: Notification[] }`만 정의 → `totalPages`·`hasNext` 접근 불가.

### 현재 페이지 UI

- `notifications/page.tsx:52` — `useNotifications()` 파라미터 없이 호출 (page=0, size=기본 20)
- `data?.content ?? []` 전량 사용
- 날짜 그룹·필터 UI는 완성됨 (클라이언트 사이드 필터)
- "더 보기" 버튼·페이저·무한 스크롤 없음

### BE 지원 현황

`be-api-alignment-mvp-r1` (Done) — `GET /notifications?page=0&size=20&sort=createdAt,desc` 정상 응답 확인. 추가 BE 작업 불필요.

## 요구사항

- [ ] **R1** `NotificationListParams` 타입에 BE `PageResponse` 메타 타입 추가
  ```typescript
  export interface NotificationPage {
    content: Notification[];
    page: { size: number; number: number; totalElements: number; totalPages: number };
  }
  ```
- [ ] **R2** `useNotifications` 훅 반환 타입을 `NotificationPage`로 변경 — `apiClient<NotificationPage>`
- [ ] **R3** `notifications/page.tsx` — `hasNext` 판별 로직 추가
  ```typescript
  const meta = data?.page;
  const hasNext = meta ? meta.number + 1 < meta.totalPages : false;
  ```
- [ ] **R4** "더 보기" 버튼 UI — 목록 하단에 추가
  - 버튼 클릭 시 `page + 1` 요청 → 결과를 기존 목록에 **append** (로컬 state 누적)
  - `hasNext === false`이면 버튼 숨김
  - 로딩 중 버튼 `disabled` + 스피너
  - 버튼 레이블: "이전 알림 더 보기"
- [ ] **R5** `useNotifications` 훅에 `page` 파라미터 전달 지원 확인 (이미 `params?.page` 지원 — R2에서 타입만 수정)
- [ ] **R6** 클라이언트 사이드 필터(ALL/UNREAD/POSITIVE/NEGATIVE)는 **누적된 전체 목록**에 적용 유지 (기존 로직 그대로)
- [ ] **R7** 접근성 — 더 보기 버튼 `aria-label="이전 알림 더 보기"`, 로딩 중 `aria-busy="true"`

## 영향 범위 (조사 결과)

- **영향 레이어**: frontend (`/notifications` 페이지, `lib/api/notifications.ts`)
- **영향 파일**:
  - `frontend/src/lib/api/notifications.ts` — `NotificationPage` 타입 추가, `useNotifications` 반환 타입 변경
  - `frontend/src/app/(app)/notifications/page.tsx` — `hasNext` 판별 + "더 보기" 버튼 추가, 누적 state 관리
- **DB 변경**: 없음
- **BE 변경**: 없음 — PageResponse 이미 반환 중
- **외부 계약**: 없음

## 관련 패턴 / 과거 사례

- `frontend/src/lib/api/disclosures.ts` — 유사 `DisclosurePage` 타입 존재 여부 확인 필요 (공시 목록도 동일 패턴 적용 가능).
- `be-api-alignment-mvp-r1` (Done) — "FE에 실제 페이지 이동 UI가 없음 → 무한스크롤/페이저는 후속 FE 카드로 분리" 명시. 본 Spec이 그 후속.
- `useDelayedLoading` — 이미 `notifications/page.tsx`에서 사용 중. 추가 페이지 요청 시 재사용 가능.

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| 클라이언트 필터가 현재 페이지만 적용 | 누적 state(`allNotifications`)에 필터 적용 — 부분 데이터 필터 오동작 방지 |
| 읽음 처리(markAsRead) 후 쿼리 무효화 → 누적 목록 초기화 | `invalidateQueries` 시 page=0으로 리셋 수용 (MVP). 정밀 해결은 optimistic update — 후속 |
| BE size 기본값 불명확 | `useNotifications` 호출 시 `size: 20` 명시적 전달 |

## 권장 구현 방향

**"더 보기" 버튼 방식** 선택 — 무한 스크롤은 IntersectionObserver 필요, 복잡도 증가. 버튼 방식은:
- 사용자가 의도적으로 로드 → 불필요한 API 호출 없음
- 시니어 페르소나(C) 친화적 — 예측 가능한 동작
- 구현 복잡도 최소

로컬 `useState<Notification[]>` 누적 → 필터는 누적 배열에 적용 → 날짜 그룹화는 기존 `groupByDate` 재사용.

```typescript
// 핵심 구조
const [allItems, setAllItems] = useState<Notification[]>([]);
const [page, setPage] = useState(0);
const { data, isFetching } = useNotifications({ page, size: 20 });

useEffect(() => {
  if (data?.content) {
    setAllItems(prev => page === 0 ? data.content : [...prev, ...data.content]);
  }
}, [data, page]);

const hasNext = data?.page ? data.page.number + 1 < data.page.totalPages : false;
```

## Tech Review (dc-tech-review · 2026-06-25)

### ⚠️ Spec 정정 사항 (BE 코드 직접 확인 결과)

BE `PageResponse.PageMeta`(`shared/dto/PageResponse.java`)와 `NotificationController`(`user/controllers/`)를 직접 읽고 확인한 결과, Spec 본문의 가정 2건이 실제와 다름:

1. **[필드명 오류 — 중요]** Spec R1/R3은 `totalPages`·`totalElements`(camelCase)로 가정했으나, **실제 BE 응답은 snake_case** — `total_pages`·`total_elements`. `PageResponse.PageMeta`가 `@JsonProperty("total_pages")`/`@JsonProperty("total_elements")`로 직렬화. 이미 `disclosures.ts`의 `DisclosurePage`가 동일하게 snake_case로 정의돼 있음.
   - 올바른 타입:
     ```typescript
     export interface NotificationPage {
       content: Notification[];
       page: { number: number; size: number; total_elements: number; total_pages: number };
     }
     ```
   - 올바른 hasNext 판별:
     ```typescript
     const meta = data?.page;
     const hasNext = meta ? meta.number + 1 < meta.total_pages : false;  // total_pages (snake)
     ```

2. **[sort 파라미터 미지원]** `NotificationController.list()`는 `page`·`size`만 받고 **`sort`는 바인딩하지 않음** — `createdAt DESC` 고정(sort 필드 인젝션 방지, L26 주석). FE `NotificationListParams.sort`를 보내도 BE가 무시하므로 무해하나, **R5에서 sort 전달은 불필요**. 혼선 방지 위해 `useNotifications` 호출 시 sort 미전달 권장.

### 아키텍처 분해

- **영향 레이어**: frontend 단독 (`lib/api/notifications.ts` + `app/(app)/notifications/page.tsx`). BE·DB 변경 없음.
- **신규**: `NotificationPage` 타입 (disclosures.ts `DisclosurePage` 패턴 복제)
- **수정**: `useNotifications` 반환 타입, `notifications/page.tsx` 누적 state + 더 보기 버튼
- **재사용 자산**:
  - `disclosures.ts:59-62` `DisclosurePage` — 타입 구조 1:1 복제 대상 (snake_case 확정 SSOT)
  - `notifications/page.tsx`의 `groupByDate`·필터·`useDelayedLoading` — 그대로 유지
  - `PageResponse<NotificationResponse>` (BE) — FE 타입의 SSOT

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `NotificationPage` 타입 추가 (snake_case) + `useNotifications` 반환 타입 `apiClient<NotificationPage>`로 변경 | frontend/lib | FE | 하 | - |
| 2 | `notifications/page.tsx` 누적 state(`allItems`) + `page` state + append useEffect | frontend/app | FE | 중 | #1 |
| 3 | "더 보기" 버튼 UI (hasNext 판별 + disabled/스피너 + aria-label/aria-busy) | frontend/app | FE | 하 | #2 |
| 4 | 클라이언트 필터(ALL/UNREAD/POSITIVE/NEGATIVE)를 누적 `allItems`에 적용 확인 + markAsRead 무효화 시 page=0 리셋 동작 검증 | frontend/app | FE | 중 | #2 |

### DB / 마이그레이션 영향

- **없음**. BE PageResponse 이미 반환 중. Flyway 마이그레이션 불필요.

### 외부 계약 영향

- **없음**. DART/KRX/카카오/LLM 무관. 자체 REST `GET /api/v1/notifications` 계약 변경 없음 (이미 page/size 지원).

### 리스크 & 법적 검토

- **[상태 동기화 — 중]** `markAsRead`/`markAllAsRead` mutation이 `["notifications"]` 쿼리를 invalidate하면 page=0부터 재조회 → 누적 `allItems` 리셋됨. MVP 수용 가능(읽음 처리 후 첫 페이지 복귀는 자연스러운 UX). 단 사용자가 여러 페이지 로드 후 읽음 처리 시 스크롤 위치 손실 — 정밀 해결(optimistic update)은 후속 분리.
- **[필터 정합성 — 중]** UNREAD 필터가 누적되지 않은 페이지의 안읽음을 놓칠 수 있음(서버 페이지네이션 + 클라이언트 필터의 구조적 한계). MVP는 "로드된 범위 내 필터"로 한정, 안내 문구 불필요(기존 동작과 동일 수준).
- **[자본시장법/개인정보]** 해당 없음 — 알림 메타(corp_name·report_nm·sentiment)만 표시, 매수가·보유량 등 금융 개인정보 미노출. 투자 권유 표현 없음.
- **[접근성]** R7 더 보기 버튼 `aria-label`·`aria-busy` 필수 (CLAUDE.md §6-5). 키보드 포커스 경로 유지.

### 예상 wave 수

- **1 wave** (단일 PR). 카드 #1→#2→#3→#4 순차, FE 단독 변경. `/dc-review-frontend` Playwright 검증 권장(더 보기 버튼 동작 + 접근성).

### 구현 진입 전 결정 사항

1. **markAsRead 후 리셋 수용 여부** — MVP는 page=0 리셋 수용(권장). 정밀 동기화는 후속.
2. **size 값** — `useNotifications({ page, size: 20 })` 명시 전달. BE default도 20이라 일치.

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
