---
type: spec
status: Done
created: 2026-07-05
updated: 2026-07-05
---

# 공시 날짜 표기 통일 — DisclosureCard 원시 포맷 제거 + 공용 포맷 유틸 승격 Spec

> 상태: Approved → **Done** (2026-07-05, 구현·5페르소나리뷰·실앱검증 통과)
> 우선순위: **시연 크리티컬** — 대시보드(앱 홈)에서 모든 공시 행이 미가공 날짜를 노출.

## 배경 / 목적

- `DisclosureCard`(`frontend/src/components/domain/DisclosureCard.tsx:69-70`)가 `rcept_dt`를 가공 없이 렌더 → 화면에 `20260705`(YYYYMMDD)로 노출.
- 이 카드는 **대시보드 피드**·**공시 피드(/disclosures)**·**대시보드 프리뷰** 3곳에서 사용됨 → 앱 홈에서 항상 보이는 미완성 인상.
- 반면 `/portfolios`의 "종목별 최근 공시" 패널은 `formatRelativeTime()`으로 `"7시간 전"` 상대시간을 표시 → **같은 데이터가 화면마다 다른 포맷**.
- **시연 리스크**: 대시보드는 페르소나 A/C가 매일 방문하는 핵심 화면([[dashboard-recent-3days]]). 시연 시 모든 공시 행의 `20260705`가 즉시 눈에 띄어 제품 완성도를 훼손.
- BM 티어: 전 티어 공통 UI. 데이터 표시 정합만 다룸.

## 요구사항

- [ ] `DisclosureCard`의 날짜가 사람이 읽는 포맷(상대시간 "N시간 전" 또는 `YYYY-MM-DD`)으로 표시
- [ ] `portfolios/page.tsx`의 `formatRelativeTime()`을 **공용 유틸로 승격**(`frontend/src/lib/date/`) 후 DisclosureCard·portfolios가 동일 함수 재사용 — 표기 이원화 제거
- [ ] YYYYMMDD(예: `20260705`)와 ISO 문자열을 모두 입력으로 허용(기존 `formatRelativeTime` 정규화 로직 계승)
- [ ] `<time dateTime={...}>`의 machine-readable 속성은 ISO(`YYYY-MM-DD`)로 유지(접근성/SEO) — 표시 텍스트만 사람이 읽는 포맷
- [ ] 공용 유틸에 Vitest 케이스(YYYYMMDD 입력·ISO 입력·경계 상대시간: 방금/분/시간/일) 추가
- [ ] 사용처 3곳(dashboard·disclosures·dashboard/preview) 회귀 확인

## 영향 범위 (조사 결과)

- 영향 레이어: **frontend** 단독. BE/DB/외부 계약 변경 없음
- 영향 파일:
  - `frontend/src/components/domain/DisclosureCard.tsx` — L69-70 날짜 렌더를 공용 유틸 호출로 교체
  - `frontend/src/app/(app)/portfolios/page.tsx` — L52-64 `formatRelativeTime` 제거 → 공용 유틸 import
  - 신규: `frontend/src/lib/date/formatDisclosureDate.ts` (+ `.test.ts`) — `shiftDateStr`와 같은 `lib/date/` 배치
  - 사용처(회귀 확인만): `dashboard/page.tsx`, `disclosures/page.tsx`, `dashboard/preview/page.tsx`
- DB 변경: 없음

## 관련 패턴 / 과거 사례

- `frontend/src/lib/date/shiftDateStr.ts` — 최근 도입된 `lib/date/` 순수 유틸 + Vitest 패턴. 신규 포맷 유틸도 동일 위치·테스트 규약 따름
- `portfolios/page.tsx`의 기존 `formatRelativeTime` — 이미 YYYYMMDD 정규화 + 상대시간 로직 보유. **재작성이 아닌 승격**(로직 이동)
- `[[be-api-alignment-mvp-r1]]` — 과거 `rcept_dt` YYYYMMDD 형식 정합 이력. BE는 YYYYMMDD 유지가 계약이므로 FE 표시 계층에서만 변환

## 리스크 / 법적 검토

- DisclosureCard는 **공유 컴포넌트** — 수정이 3개 사용처에 동시 영향. **[확인 완료 · Tech Review 2026-07-05]** `/disclosures`의 `groupByDate`(page.tsx:33-44)는 `d.rcept_dt` **원본 필드**를 Map 키로 읽으므로 카드의 `<time>` 표시 텍스트 변경과 **무관**(그룹 로직 안전). 단, 같은 함수가 오늘/어제가 아닌 그룹의 **헤더 라벨로 `20260609` 원시 날짜를 그대로 노출**(page.tsx:39 `: d.rcept_dt`) → **두 번째 원시 노출 지점**이 존재. 본 Spec의 "표기 통일" 목적상 함께 다뤄야 함(Tech Review 카드 #4)
- 자본시장법/개인정보: 해당 없음 — 날짜 표기 변경만
- 접근성(§6-5): `<time dateTime>` ISO 유지로 스크린리더·기계 판독 보존

## 권장 구현 방향

- **`formatDisclosureDate(rcept_dt)` 순수 함수 승격** (권장):
  - 입력: YYYYMMDD 또는 ISO → 내부 정규화(기존 `formatRelativeTime` 로직 이동)
  - 반환: 상대시간 문자열("방금 전"/"N분 전"/"N시간 전"/"N일 전"). N일 초과 시 `YYYY-MM-DD` 폴백 검토(오래된 공시가 "30일 전"보다 절대날짜가 읽기 쉬움)
  - `Date.now()` 의존이므로 순수성 보장 위해 테스트에서 기준시각 주입 가능하도록 2번째 인자(`now`) 옵션 파라미터 추가 검토
- 대안(비권장): DisclosureCard만 국소 수정 — 표기 이원화가 남아 부채 지속. 승격이 정답
- 확정 필요(Tech Review): 상대시간 임계값(며칠 지나면 절대날짜로 전환할지) — 기본 제안: 7일 초과 시 `YYYY-MM-DD`

## Tech Review (dc-tech-review · 2026-07-05)

### 아키텍처 분해
- 영향 레이어: **frontend** 단독. BE/DB/외부 계약 변경 없음
- 신규 vs 수정:
  - 신규: `frontend/src/lib/date/formatDisclosureDate.ts` (+ `.test.ts`) — `shiftDateStr`와 같은 `lib/date/` 위치·규약
  - 수정: `DisclosureCard.tsx`(표시 텍스트), `portfolios/page.tsx`(로컬 `formatRelativeTime` 제거), `disclosures/page.tsx`(그룹 헤더 라벨)
- 표시 계층 전용 변경 — 데이터 필드(`rcept_dt` 원본 YYYYMMDD)·BE 계약·`<time dateTime>` ISO 속성 모두 불변

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | `formatDisclosureDate(rcept_dt, now?)` 순수 함수 승격 — portfolios `formatRelativeTime`(page.tsx:52-64) 로직 이동 + YYYYMMDD/ISO 정규화 + 7일 초과 시 `YYYY-MM-DD` 폴백. `now` 옵션 인자로 테스트 결정성 확보 | frontend/lib | FE | 하 | - |
| 2 | `formatDisclosureDate` Vitest — YYYYMMDD 입력·ISO 입력·경계(방금/분/시간/일)·7일 폴백·now 주입 | frontend/lib | FE | 하 | #1 |
| 3 | `DisclosureCard.tsx:69-71` 날짜 표시를 `formatDisclosureDate` 호출로 교체. `dateTime`은 ISO(`YYYY-MM-DD` 정규화값)로 유지 | frontend/components | FE | 하 | #1 |
| 4 | `portfolios/page.tsx` 로컬 `formatRelativeTime` 제거 → 공용 유틸 import (표기 이원화 제거) | frontend/portfolios | FE | 하 | #1 |
| 5 | `disclosures/page.tsx` `groupByDate` 헤더 라벨의 원시 `20260609` → 사람이 읽는 라벨(예: `YYYY-MM-DD` 또는 "이번 주"). 그룹 **키**는 `d.rcept_dt` 원본 유지(정렬·삽입순서 보존), 표시 라벨만 변환 | frontend/disclosures | FE | 중 | #1 |
| 6 | 사용처 회귀 확인 — dashboard 피드·/disclosures·**dashboard/preview 목업(하드코딩 rcept_dt "20260611")** 렌더 + typecheck + Vitest 전건 | frontend | FE | 하 | #3~#5 |

### DB / 마이그레이션 영향
- 없음 — Flyway 마이그레이션 불필요 (표시 계층 전용)

### 외부 계약 영향
- DART/KRX/카카오/LLM: 없음
- 자체 REST: 없음 — `rcept_dt` 응답 필드(YYYYMMDD)는 계약 그대로. FE 표시 변환만

### 리스크 & 법적 검토
- **design_structure §241 "원문 인용 필드(…날짜)는 원본 그대로 렌더 — LLM 변형 금지"와의 관계**: 본 변경은 **표시 포맷팅**(YYYYMMDD→상대시간/ISO)이지 LLM에 의한 값 변형이 아님. 원본 값은 보존되고 `<time dateTime>`에 ISO로 남아 기계 판독·검증 가능 → **규칙 위반 아님**. 단, §241의 취지(수치·회사명 등 팩트 왜곡 금지)와 구분되도록 구현 시 "표시 변환만, 값 불변" 주석 명시 권장
- **groupByDate UTC 잠재 버그(동일 도메인, 이번 범위 밖)**: `disclosures/page.tsx:34-35`의 today/yesterday가 `new Date().toISOString()`(**UTC**) 기준 — Asia/Seoul 자정~09:00 구간에 "오늘/어제" 그룹이 하루 어긋날 수 있음. 카드 #5 작업 시 `useTodaySeoul`/Seoul 기준으로 함께 정합하면 이상적이나, 그룹 키 변경은 회귀 위험이 있으므로 **별도 판단**(카드 #5에 포함할지 후속 분리할지 — 구현 시 확정). 미포함 시 이슈로 잔류 명시
- **dashboard/preview 목업 드리프트**: 하드코딩 `rcept_dt: "20260611"`은 `Date.now()` 기준 상대시간이면 "N일 전"이 시간에 따라 증가(오늘 기준 24일 전 등). **7일 초과 절대날짜 폴백(카드 #1)이 있으면 `2026-06-11`로 안정 렌더** → 드리프트 문제 해소. 폴백 임계값(기본 7일) 확정 필요
- **테스트 결정성**: 상대시간은 `Date.now()` 의존 → 카드 #1에서 `now` 옵션 인자 필수(주입 없으면 Vitest가 실행 시각에 따라 흔들림)
- 자본시장법/개인정보: 해당 없음

### 예상 wave 수
- **1 wave** (FE 단일 기능). 시연 크리티컬이므로 카드 #3(DisclosureCard=대시보드 노출)이 최우선. 카드 #5(그룹 헤더)는 같은 유틸을 쓰므로 동일 wave 권장하나, 회귀 부담 시 분리 가능(그때는 카드 #5 미완 = /disclosures 오래된 그룹 헤더에 원시 날짜 잔류를 이슈로 명시)

### 판정
- **구현 가능 (Approved 전환 권장)** — 핵심 미확인(그룹 로직 영향)은 검증 완료(로직 안전 + 헤더 라벨은 별도 노출 지점으로 카드화). 확정 대기 2건은 구현 시 결정 가능한 경미 사항:
  1. 상대→절대 전환 임계값(기본 제안 **7일**)
  2. groupByDate UTC→Seoul 정합을 이번 wave에 포함할지 (기본 제안: **포함하되 그룹 키는 원본 유지, 실패 시 후속 이슈 분리**)
