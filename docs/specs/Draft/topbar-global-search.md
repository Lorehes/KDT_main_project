---
type: spec
status: Draft
created: 2026-06-25
updated: 2026-06-25
---

# TopBar 글로벌 검색 Spec

> 상태: **Draft** (dc-plan 생성)

## 배경 / 목적

TopBar에 "종목명 또는 공시 검색" 입력창이 존재했으나 미구현 상태로 제거됨 (2026-06-22).
검색창 부재로 투자자(페르소나 A~D)가 원하는 공시·종목을 찾으려면 공시 피드 전체를 스크롤해야 함.
키워드 → 공시 피드(`/disclosures?q=...`)로 라우팅하는 MVP 검색 기능을 구현한다.

- 검색 대상: 공시 제목(`report_nm`) + 회사명(`corp_name`)
- BM 티어: 전 티어 공통 (단, Free 티어는 BE Free 제한 그대로 — 오늘 날짜·5건 상한 유지)

## 요구사항

### BE
- [ ] **R1** `GET /api/v1/disclosures`에 `q` 쿼리 파라미터 추가 (optional, max 100자)
- [ ] **R2** `q` 미입력 시 기존 동작 완전 보존 — 하위 호환
- [ ] **R3** `q` 입력 시 `report_nm ILIKE '%q%' OR corp_name ILIKE '%q%'` 조건 추가 (대소문자 무시)
- [ ] **R4** `q`는 기존 scope/sentiment/날짜/stockCode 필터와 AND 조합 — 복합 필터 정상 작동
- [ ] **R5** Free 티어 날짜·size 강제 정책은 `q` 유무와 무관하게 그대로 적용
- [ ] **R6** `q` 빈 문자열("")은 null과 동일하게 처리 (필터 없음)

### FE — TopBar
- [ ] **R7** TopBar 네비 우측에 검색 입력창 추가 (`<input type="search">`)
- [ ] **R8** Enter 키 입력 → `/disclosures?q={keyword}`로 라우팅 (useRouter.push)
- [ ] **R9** 빈 문자열 Enter → 라우팅 없음 (무시)
- [ ] **R10** 라우팅 완료 후 입력창 값 초기화
- [ ] **R11** `aria-label="공시·종목명 검색"`, `role="search"` 적용 (WCAG 2.1 AA, CLAUDE.md §6-5)
- [ ] **R12** 모바일(md 미만) — 검색창 숨김 (`hidden md:flex`). 모바일 검색은 후속 Spec

### FE — 공시 피드 페이지 (`/disclosures`)
- [ ] **R13** `useSearchParams()`로 URL `q` 파라미터 읽기 (Next.js App Router)
- [ ] **R14** `q` 값을 `useDisclosures({ ..., q })` 훅에 전달
- [ ] **R15** `q`가 있을 때 페이지 상단에 `"'삼성전자' 검색 결과"` 안내 문구 표시
- [ ] **R16** `q` 변경 시 페이지·누적 상태 리셋 (기존 필터 변경 패턴과 동일)

### FE — `useDisclosures` 훅
- [ ] **R17** `DisclosureListParams`에 `q?: string` 추가
- [ ] **R18** `q`가 undefined/빈 문자열이면 URLSearchParams에서 생략

## 영향 범위

### Backend
- `backend/src/main/java/com/dartcommons/disclosure/controllers/DisclosureController.java` — `q` @RequestParam 추가
- `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureQueryService.java` — `q` 파라미터 수용, 4개 쿼리 분기 처리
- `backend/src/main/java/com/dartcommons/disclosure/repositories/DisclosureRepository.java` — 기존 4개 JPQL/native 쿼리에 `q` ILIKE 조건 추가

### Frontend
- `frontend/src/components/layout/TopBar.tsx` — 검색 입력창 추가
- `frontend/src/app/(app)/disclosures/page.tsx` — useSearchParams + q 연동, 안내 문구
- `frontend/src/lib/api/disclosures.ts` — `DisclosureListParams`에 `q` 추가

### DB / 외부 계약
- DB 변경: **없음** (Flyway 마이그레이션 불필요)
- 외부 API 변경: **없음**

## 관련 패턴

- 기존 종목 검색: `GET /api/v1/stocks/search?q=...` + `useStockSearch(q)` (`StockRepository.search()` ILIKE 패턴, `StockRepository:45`)
- 기존 공시 필터 쿼리: `DisclosureRepository` 4개 메서드 — null 처리는 `CAST(:param AS date) IS NULL` PostgreSQL 패턴 사용 중
- Free 티어 강제: `DisclosureQueryService.list()` — q 추가 시 이 블록 통과 이후 적용
- TopBar 라우팅 패턴: `useRouter` + `usePathname` 이미 사용 중

## 리스크

- ILIKE 성능: `disclosures` 테이블이 대량 데이터 시 `report_nm ILIKE '%q%'` full table scan 위험. 단 MVP 단계에서 수만 건 이하 예상 — `(stock_code, rcept_dt DESC)` 복합 인덱스(미구현)와 함께 후속 최적화. 현재는 pg_trgm 인덱스 없이 진행.
- 쿼리 4개 수정: `DisclosureRepository`에 기존 4개 쿼리(portfolio/all × sentiment 있음/없음)에 각각 q 조건 추가 필요 — 변경 범위 넓음, 각 쿼리 개별 테스트 필요.
- `CAST(:q AS text) IS NULL` 패턴: 날짜와 동일한 null 처리 패턴 적용. native query에서는 `:q IS NULL` 직접 불가 (OID 미지정 오류) — CAST 패턴 필수.
- `useSearchParams()` Suspense: Next.js 15 App Router에서 `useSearchParams()`는 클라이언트 컴포넌트 필수 + Suspense 경계 필요. 공시 피드 페이지는 이미 `"use client"` — Suspense는 상위 레이아웃에 이미 존재하는지 확인 필요.

## 권장 구현 방향

### BE: 기존 4개 쿼리에 q 조건 추가

```sql
-- 추가할 AND 조건 (native query 기준)
AND (CAST(:q AS text) IS NULL OR report_nm ILIKE '%' || :q || '%' OR corp_name ILIKE '%' || :q || '%')
```

JPQL 쿼리(findFilteredByStocks, findAllFiltered)도 동일하게 `LOWER` 없이 `ILIKE` 직접 사용 가능 (HQL ILIKE Hibernate 6 지원 확인 필요 — 불가 시 native query로 전환 또는 LOWER+LIKE 사용).

`DisclosureQueryService.list()` 시그니처에 `String q` 추가, `q` 빈 문자열 → null 정규화.

### FE: TopBar 검색창 — Enter → 라우팅

```tsx
// TopBar.tsx
const router = useRouter();
const [searchQ, setSearchQ] = useState("");

function handleSearch(e: React.KeyboardEvent<HTMLInputElement>) {
  if (e.key !== "Enter" || !searchQ.trim()) return;
  router.push(`/disclosures?q=${encodeURIComponent(searchQ.trim())}`);
  setSearchQ("");
}
```

- shadcn/ui `Input` 컴포넌트 사용
- 위치: 네비게이션 링크와 우측 아이콘 그룹 사이 (`flex-1 max-w-xs`)

### FE: 공시 피드 페이지 URL 연동

```tsx
// disclosures/page.tsx
const searchParams = useSearchParams();
const q = searchParams.get("q") ?? undefined;

// useDisclosures에 q 전달
const { data } = useDisclosures({ scope: "portfolio", ..., q });

// q 변경 시 리셋 (기존 filter useEffect 패턴과 동일)
useEffect(() => {
  setPage(0);
  setAllItems([]);
}, [q, filter]);
```

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
