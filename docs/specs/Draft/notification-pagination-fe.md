---
type: spec
status: Draft
created: 2026-06-25
updated: 2026-06-25
---

# 알림 목록 페이지네이션 FE Spec

> 상태: **Draft** (dc-plan 생성)
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

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
