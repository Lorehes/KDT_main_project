---
type: spec
status: Approved
created: 2026-06-09
updated: 2026-06-10
---

# FE 정확성 · 투자자 보호 보강 Spec

> 상태: **Approved** (2026-06-10, dc-tech-review 승인 — R2 이미 구현, 4-wave 계획 확정)

## 배경 / 목적

FE UI 가 완성됐으나, 분석 미완료 공시에 룰 기반 sentiment 가 노출되거나 페이지네이션 메타가 어긋나는 등 **CLAUDE.md §6-6 "신뢰도 없이 호재/악재 단정 금지" 위반 + 페이지네이션 무한 로딩** 위험이 확인됐다. 본 Spec 은 FE 정확성 P1 5건을 묶어 처리한다.

- **현황**: `disclosures/[id]/page.tsx` 가 `analysis` 가 없어도 `disclosure.sentiment` 폴백, `signup/terms/page.tsx` 의 `email.split` TypeError, `portfolios/page.tsx` 로딩 중 `atLimit=false`
- **목표**: 투자자 보호 원칙 준수 + FE 런타임 에러 0
- **BM 연관**: Free 사용자 — 분석 대기 중 sentiment 노출 시 자본시장법 §11.1 경계

---

## 요구사항

### 투자자 보호

- [ ] **R1** `disclosures/[id]/page.tsx:52` 분석 미완료 시 sentiment 배지 숨김 — `const sentiment = analysis ? (analysis.sentiment ?? disclosure.sentiment) : undefined`. 분석 없으면 "분석 대기 중" 별도 배지 표시
- [ ] **R2** `confidence < 0.5` 시 `isWithheld=true` 처리 후 "판단 보류" 카드 노출 — 신뢰도 낮을 때 sentiment 표시 금지. 카드에 면책 문구 강조

### 페이지네이션 정합성

- [ ] **R3** `DisclosureQueryService.list()` sentiment 필터를 메모리 → JPQL `LEFT JOIN AnalysisResult ar ON ar.disclosureId = d.id WHERE (:sentiment IS NULL OR ar.sentiment = :sentiment)` 로 이전. `totalElements`/`totalPages` 정확해짐
- [ ] **R4** 본 Spec 머지 전까지 FE 무한 스크롤 동작 변경 — `totalElements` 가 실제 content 수보다 클 수 있다는 가정 하에 `content.length < requested size` 시 `hasMore=false` 로 강제 종료

### 런타임 에러 차단

- [ ] **R5** `signup/terms/page.tsx:53` `email?.split("@")[0]` optional chaining 적용. 추가로 `useEffect` 에서 `email` 미존재 시 `/signup` 으로 redirect
- [ ] **R6** `portfolios/page.tsx:29` `atLimit` 계산에 `isLoading` 포함 — `const atLimit = isLoading || (!isPro && count >= FREE_LIMIT)`. 로딩 중 추가 버튼 비활성

### TanStack Query / 분석 의존성

- [ ] **R7** `disclosures/[id]/page.tsx` 의 `useDisclosureAnalysis(id)` 호출에 `enabled: !!disclosure` 추가 — disclosure 가 먼저 로드된 후 분석 쿼리 실행. 미스매치 데이터 렌더 방지

---

## 영향 범위

- **영향 레이어**: backend (`disclosure/repositories`, `disclosure/services`) + frontend (`app/(app)/disclosures/[id]`, `app/(auth)/signup/terms`, `app/(app)/portfolios`)
- **DB 변경**: 없음
- **외부 계약**: API 응답 페이지 메타 정확도 향상 — FE 무한 스크롤 클라이언트 코드 수정 동반

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `frontend/src/app/(app)/disclosures/[id]/page.tsx` | R1 sentiment 가드 + R2 isWithheld + R7 enabled 의존 |
| `frontend/src/app/(auth)/signup/terms/page.tsx` | R5 optional chaining + redirect |
| `frontend/src/app/(app)/portfolios/page.tsx` | R6 atLimit 로딩 가드 |
| `frontend/src/app/(app)/disclosures/page.tsx` | R4 hasMore 강제 종료 |
| `backend/.../disclosure/repositories/DisclosureRepository.java` | R3 sentiment JPQL JOIN |
| `backend/.../disclosure/services/DisclosureQueryService.java` | R3 메모리 필터 제거 |

---

## 관련 패턴 / 과거 사례

- `analysis-stage2-llm` (Done) — `confidence < 0.6` withhold 임계치 + 면책 문구 동반 패턴
- `disclosure-collection-pipeline` (Done) — Stage 1 룰 기반 sentiment 는 "임시" 표시 원칙
- 통합기획서 §11.1 — "투자 권유 표현 금지", "신뢰도 없이 호재/악재 단정 금지"
- CLAUDE.md §6-6 — "LLM 분석에 confidence 필드 필수, 낮으면 판단 보류 표시"

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| 분석 대기 중 sentiment 미표시로 "정보 없음" UX 저하 | "분석 대기 중" 명시적 배지 + 예상 완료 시간 안내 |
| JPQL JOIN 추가로 공시 피드 쿼리 비용 증가 | `(disclosure_id, sentiment)` 복합 인덱스 검토 — `analysis_results.idx_analysis_disclosure` 기존 인덱스 활용 가능. 본 Spec 머지 후 EXPLAIN 으로 검증 |
| `atLimit` 로딩 가드로 첫 진입 시 추가 버튼 잠시 비활성 | 의도된 동작 — 사용자 혼동보다 false-positive 방지 우선 |
| FE hasMore 강제 종료가 페이지 누락 유발 | R3 완료 시 R4 제거 — 임시 가드 |

---

## 권장 구현 방향

- R3 (BE 페이지네이션) + R4 (FE 임시 가드) 동시 머지 → 머지 후 R4 제거 PR 별도
- R1·R2 는 [[security-hardening-mvp]] R1 (IDOR) 와 동시 진행 가능 — 동일 페이지(`disclosures/[id]`) 수정
- R5 는 [[architecture-refactoring-cleanup]] 의 `signupStore` 정합과 연동 — store 가 비어 있을 때의 redirect 로직 일관화

## Tech Review (dc-tech-review · 2026-06-10)

### 코드 vs Spec 대조 결과

| 요구사항 | 현재 코드 상태 | 작업 필요 |
|----------|---------------|----------|
| R1 sentiment 가드 | `analysis?.sentiment ?? disclosure.sentiment` — analysis null 시 룰 기반 노출 | ✅ 수정 필요 |
| R2 isWithheld 처리 | `isWithheld` 로직 + 판단 보류 UI **이미 구현됨** (line 52-95) | ⬛ 완료 (skip) |
| R3 BE 페이지네이션 | 서비스 메모리 필터링 확인. Disclosure ↔ AnalysisResult JPA 관계 매핑 없음 | ✅ native query 필요 |
| R4 FE hasMore 가드 | 피드 페이지 페이지네이션 버튼 없음 | ✅ 구현 필요 |
| R5 email optional | `email.split("@")[0]` — email undefined 시 TypeError | ✅ optional chaining + redirect |
| R6 atLimit isLoading | `atLimit = !isPro && count >= FREE_LIMIT` — isLoading 시 오활성 | ✅ 수정 필요 |
| R7 analysis enabled | `useDisclosureAnalysis` enabled 옵션 미지원 | ✅ 훅 시그니처 수정 |

### 아키텍처 분해

- **영향 레이어**: BE(disclosure/repositories, disclosure/services) + FE(app/disclosures/[id], app/disclosures, app/portfolios, app/(auth)/signup/terms, lib/api/disclosures.ts)
- **신규**: 없음
- **수정 대상**: DisclosureRepository(R3 native query), DisclosureQueryService(R3 메모리 필터 제거), useDisclosureAnalysis 훅(R7 enabled 옵션), 공시 상세 페이지(R1·R7), 공시 피드 페이지(R4), 포트폴리오 페이지(R6), 약관 페이지(R5)

### 작업 카드

| # | 작업 | 레이어 | 난이도 | 의존성 |
|---|------|--------|--------|--------|
| 1 | `signup/terms`: email optional chaining + 미존재 시 `/signup` redirect | FE/(auth) | 하 | - |
| 2 | `portfolios`: `atLimit = isLoading \|\| (!isPro && count >= FREE_LIMIT)` | FE/(app) | 하 | - |
| 3 | `disclosures.ts`: `useDisclosureAnalysis` opts.enabled 파라미터 추가 | FE/lib | 하 | - |
| 4 | `disclosures/[id]`: R1 sentiment 가드 + "분석 대기 중" 배지 + R7 enabled 연결 | FE/(app) | 하 | #3 |
| 5 | `DisclosureRepository`: sentiment JOIN native query 2종 추가 | BE/disclosure | 중 | - |
| 6 | `DisclosureQueryService`: 메모리 필터 → DB 쿼리 위임 | BE/disclosure | 하 | #5 |
| 7 | `disclosures/page`: "더 보기" 버튼 + `content.length < size` hasMore 가드 | FE/(app) | 하 | - |

### DB / 마이그레이션 영향

- **마이그레이션 불필요**: R3은 기존 스키마 구조 그대로 — `analysis_results.disclosure_id` 컬럼에 기존 인덱스 `idx_analysis_disclosure` 활용
- **native query 선택 이유**: `Disclosure` ↔ `AnalysisResult` 간 JPA @OneToOne 매핑 없음 → JPQL JOIN 불가. `@Query(nativeQuery=true)`로 `LEFT JOIN analysis_results ar ON ar.disclosure_id = d.id WHERE (:sentiment IS NULL OR ar.sentiment = :sentiment)` 처리. countQuery 별도 필요.
- **wave 완료 후 검토**: EXPLAIN ANALYZE로 sentiment JOIN 인덱스 활용 확인 권장 (기존 idx_analysis_disclosure)

### 외부 계약 영향

- DART API 없음. API 응답 스키마 변경 없음(페이지 메타 `total_elements` 정확도만 개선).

### 리스크 & 법적 검토

| 리스크 | 대응 |
|--------|------|
| R1: 분석 미완료 공시에 룰 기반 sentiment 표시 → 자본시장법 §11.1 "신뢰도 없이 호재/악재 단정" | `analysis` null 시 sentiment undefined 처리 + "분석 대기 중" 명시 배지 |
| R3 native query — Hibernate dialect 의존성 | PostgreSQL 전용 문법 미사용. ANSI SQL `LEFT JOIN` + IS NULL 패턴으로 충분 |
| R7 enabled 추가로 analysis 쿼리 지연 → 페이지 로딩 시간 증가 | disclosure(~50ms) 완료 후 analysis 시작 — 직렬 레이턴시 소폭 증가. 수용 가능(UX > 법적 리스크) |
| R4 hasMore 가드 임시성 — R3 완료 시 제거해야 함 | WORKLOG "다음 작업"에 명시, R3 머지 후 R4 가드 제거 PR |

### 예상 wave 수

- **Wave 1**: Card #1 #2 #3 (FE 독립 방어 코드 — 사이드이펙트 없음)
- **Wave 2**: Card #4 (R1 sentiment 가드 + R7 연결 — #3 의존)
- **Wave 3**: Card #5 #6 (BE R3 native query + 서비스 리팩)
- **Wave 4**: Card #7 (R4 FE hasMore 가드 — BE와 독립이지만 R3 후 제거 대상이므로 마지막)
