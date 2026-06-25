---
type: spec
status: Approved
created: 2026-06-25
updated: 2026-06-25
---

# TopBar 글로벌 검색 Spec

> 상태: Draft → **Approved** (2026-06-25, dc-tech-review 승인)

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

## Tech Review (dc-tech-review · 2026-06-25)

### 아키텍처 분해

- 영향 레이어: `backend/disclosure` (Controller → Service → Repository) / `frontend/TopBar` + `frontend/disclosures`
- 신규: 없음
- 수정: `DisclosureRepository` 4쿼리, `DisclosureQueryService.list()`, `DisclosureController`, `DisclosureListParams`, `disclosures/page.tsx`, `TopBar.tsx`

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `DisclosureRepository` JPQL 2쿼리(`findFilteredByStocks`, `findAllFiltered`)에 `(:q IS NULL OR LOWER(d.reportNm) LIKE LOWER(CONCAT('%',:q,'%')) OR LOWER(d.corpName) LIKE LOWER(CONCAT('%',:q,'%')))` 조건 추가 | BE / Disclosure | BE | 하 | - |
| 2 | `DisclosureRepository` native 2쿼리(`findFilteredByStocksWithSentiment`, `findAllFilteredWithSentiment`)에 `(CAST(:q AS text) IS NULL OR report_nm ILIKE '%' \|\| :q \|\| '%' OR corp_name ILIKE '%' \|\| :q \|\| '%')` 조건 추가 | BE / Disclosure | BE | 하 | - |
| 3 | `DisclosureQueryService.list()` 시그니처에 `String q` 추가, 빈 문자열→null 정규화(`q.isBlank()`→null), 4개 repository 호출에 q 전달 | BE / Disclosure | BE | 하 | #1 #2 |
| 4 | `DisclosureController.list()` — `@RequestParam(required=false) @Size(max=100) String q` 추가, service 전달 | BE / Disclosure | BE | 하 | #3 |
| 5 | `disclosures.ts` — `DisclosureListParams`에 `q?: string` 추가, `useDisclosures` URLSearchParams 빌드에 q 반영 | FE | FE | 하 | - |
| 6 | `disclosures/page.tsx` — `useSearchParams()` 추가 + **로컬 Suspense 래핑**(AppLayout에 없음, `portfolios/add/page.tsx` 패턴 참조), q→useDisclosures 전달, 안내 문구, `useEffect([q, filter])` 리셋 | FE | FE | 중 | #5 |
| 7 | `TopBar.tsx` — `useRouter` import + `useState<string>("searchQ")`, Enter 핸들러(`router.push('/disclosures?q=...')`), `setSearchQ("")`, `aria-label="공시·종목명 검색"`, `role="search"`, `hidden md:flex` | FE | FE | 중 | - |

### DB / 마이그레이션 영향

- **없음** — 기존 컬럼(`report_nm`, `corp_name`) 필터 조건 추가만; DDL 변경·Flyway 마이그레이션 불필요.
- 추후 최적화: `pg_trgm` 인덱스 (`CREATE INDEX ON disclosures USING gin(report_nm gin_trgm_ops)`) — 현재 MVP는 full scan 수용.

### 외부 계약 영향

- **없음** — DART/KRX API 파싱·카카오 알림톡 템플릿·LLM 프롬프트 변경 없음.

### 리스크 & 법적 검토

- **JPQL ILIKE 미지원 우회**: JPQL(HQL)에서 `ILIKE`는 Hibernate 6.1+ 지원이지만 ORM 버전 확인 전 안전하게 `LOWER(d.x) LIKE LOWER(CONCAT('%',:q,'%'))` 대안 사용. native 쿼리는 PostgreSQL `ILIKE` 직접 사용.
- **Suspense 경계 누락**: `(app)/layout.tsx`에 Suspense 없음 → `disclosures/page.tsx`에서 `useSearchParams()` 사용 시 빌드 경고/런타임 오류. `<Suspense fallback={null}>` 로컬 래핑 필수(카드 #6).
- **ILIKE full scan**: 대량 데이터 시 성능 이슈. MVP 수만 건 이하 수용, `pg_trgm` 인덱스를 별도 migration으로 후속 추가.
- **법적 검토**: q는 키워드 필터이며 투자 권유 표현 없음 — 자본시장법 충돌 없음.

### 예상 wave 수

- **1 wave** — BE 4카드 + FE 3카드 단일 PR. BE/FE 작업 병렬 가능(의존 없음), merge는 단일 커밋.
