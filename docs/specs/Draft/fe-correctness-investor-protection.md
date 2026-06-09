---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# FE 정확성 · 투자자 보호 보강 Spec

> 상태: **Draft** (dc-plan 생성, 2회 코드 리뷰 종합)

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

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->
