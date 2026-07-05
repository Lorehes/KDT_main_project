---
type: spec
status: Done
created: 2026-07-05
updated: 2026-07-05
---

# /dashboard 공시 피드 — 최근 3일 범위 확장 Spec

> 상태: Approved → **Done** (2026-07-05, 구현·리뷰·테스트 게이트 통과 · 헤더 카피 유지 사용자 확정)
> 선행 의존: [[portfolios-recent-disclosures-5d]] (Free 티어 5일 경계 클램프 완화 — BE)

## 배경 / 목적

- dashboard 공시 피드는 현재 **오늘 하루**(Asia/Seoul, `from=to=today`)만 조회 ([[dashboard-real-data]] R2).
- 사용자 인식은 "최근 3일"이었음 (2026-07-05 portfolios 5일 Spec 논의에서 전제 불일치 확인) → **실제로 최근 3일로 확장**하기로 확정, 별도 Spec으로 분리.
- 공시가 없는 날 대시보드가 비어 보이는 문제 완화 — 최근 3일 창이면 방문 시 볼거리가 유지됨.
- 페르소나 연결: A(직장인 단타, 매일 아침 확인)·C(시니어, 매일 방문 홈).
- BM 티어: 전 티어. Free는 [[portfolios-recent-disclosures-5d]]의 5일 경계 클램프 내에서 3일 조회 가능 (3일 ⊂ 5일). **해당 BE 완화가 선행되지 않으면 Free는 여전히 오늘만 반환됨.**

## 요구사항

- [ ] dashboard 공시 피드 조회 범위를 오늘 → **최근 3일**(오늘 포함, Asia/Seoul 달력일 `today-2 ~ today`)로 확장
- [ ] 날짜 범위는 자정 경과 시 자동 갱신 (`useTodaySeoul` 파생 — 기존 패턴 유지)
- [ ] "오늘" 전제 UI 문구 일괄 갱신:
  - StatCard "오늘 공시" → "최근 3일 공시" (또는 동급 문구)
  - 통계 카드 `aria-label="오늘 공시 통계"` → 범위 반영
  - 빈 상태 "오늘 등록 종목의 신규 공시가 없습니다." → "최근 3일 내 신규 공시가 없습니다." 수준
  - 헤더 "오늘의 내 공시 레이더" — 유지/변경 결정 필요 (브랜드 카피 성격, Tech Review에서 확정)
- [ ] Free 제한 배너(`isFreeLimited`) 문구·조건 재정의: 현재 "오늘 5건 조회 완료 — Pro 플랜에서..." (`total_elements > 5` 기준). 3일 창에서는 "오늘 5건"이 오문구가 됨 → "최근 3일 기준 5건 표시 중" 수준으로 갱신 + 조건 의미 재검토
- [ ] `size` 재검토: 현재 `size: 10` — 3일 창에서 보유 종목 많으면 부족 가능. 상향(예: 20) 검토 (Free는 BE가 5건 강제 유지)
- [ ] 피드 정렬 최신순 유지 — 3일치 혼재 시 날짜 구분 표시 여부는 선택 사항 (disclosures 페이지의 날짜 그룹 패턴 참고 가능)

## 영향 범위 (조사 결과)

- 영향 레이어: **frontend(dashboard)** — FE 단독. BE 변경은 [[portfolios-recent-disclosures-5d]]에 위임(중복 금지)
- 영향 파일:
  - `frontend/src/app/(app)/dashboard/page.tsx` — `useDisclosures({scope:"portfolio", size:10, from:today, to:today})` → `from: today-2` + 문구/aria-label/배너 갱신 + 헤더 주석 갱신
  - `frontend/src/lib/hooks/useTodaySeoul.ts` — 재사용 (변경 없음 예상). N일 파생 헬퍼를 [[portfolios-recent-disclosures-5d]]에서 만들 경우 공유
- DB 변경: 없음
- 외부 계약: 없음
- 테스트: 영향 없음 확인 완료 (2026-07-05 dc-tech-review 실측) — dashboard 참조 FE 테스트는 라우팅 테스트(`isActivePath.test.ts`, `middleware.test.ts`)뿐이며 "오늘" 문구·날짜 전제 없음. dashboard 피드 E2E 부재

## 관련 패턴 / 과거 사례

- [[dashboard-real-data]] — R2 오늘 필터 + R3 Free 강제 + R4 Free 제한 배너. 본 Spec은 R2 범위 확장 + R4 문구 갱신에 해당
- [[portfolios-recent-disclosures-5d]] — Free 5일 경계 클램프(BE)·5일 범위 파생 헬퍼(FE). **본 Spec보다 먼저 구현되어야 함** (동일 wave 묶음 처리도 가능 — Tech Review에서 순서 확정)
- 과거 회귀 사례 (dev-log, dashboard-real-data Wave 1): "오늘 필터 미전달 시 전체 기간 반환" — from/to 전달 누락 주의

## 리스크 / 법적 검토

- **의존 순서**: [[portfolios-recent-disclosures-5d]]의 BE 완화 없이 본 Spec만 배포하면 Free는 `from=today-2`를 보내도 오늘로 클램프되어 라벨("최근 3일")과 실데이터 불일치 발생 — 배포 순서 강제 필요
- **"오늘 공시" 통계 의미 변화**: StatCard 건수·감성 집계가 3일 누계로 바뀜 — 사용자가 "오늘 건수"로 오독하지 않도록 라벨 명확화 필수 (문구는 사실 표시만, 투자 권유 표현 금지 §11.1)
- Free 배너 문구는 기능 안내로 한정 (자본시장법 §11.1 — [[dashboard-real-data]] 때와 동일 기준)
- 접근성: aria-label·role=status 등 기존 구조 유지, 문구만 갱신 (WCAG §6-5)

## 권장 구현 방향

- `const from = addDaysSeoul(today, -2)` 형태로 `useTodaySeoul` 반환값 파생 — [[portfolios-recent-disclosures-5d]]의 파생 헬퍼(`오늘-4일`)와 동일 유틸을 일수 파라미터화하여 공유 (`frontend/src/lib/` 배치)
- 구현 순서: [[portfolios-recent-disclosures-5d]] (BE 클램프 + 헬퍼) → 본 Spec (dashboard 적용) — 단일 브랜치 wave 연속 처리 권장
- 문구 확정 목록(구현 전 사용자 1회 확인 권장): StatCard 라벨 / 빈 상태 / Free 배너 / 헤더 카피 유지 여부

## Tech Review (dc-tech-review · 2026-07-05)

### 아키텍처 분해
- 영향 레이어: frontend(dashboard) 단독 — BE 변경 없음 ([[portfolios-recent-disclosures-5d]] Wave 1이 담당)
- 신규 vs 수정:
  - 신규: 없음 (날짜 파생 헬퍼는 [[portfolios-recent-disclosures-5d]] 카드 #4에서 생성 — 일수 파라미터화 전제, 본 Spec은 재사용만)
  - 수정: `frontend/src/app/(app)/dashboard/page.tsx` 단일 파일 (쿼리 파라미터 + 문구 + 헤더 주석)
- IA 정합: design_structure D2/03 "보유 종목 최신 공시·분석 피드 (앱 허브)" — 3일 확장과 충돌 없음. 신규 시각 요소 없음(토큰 무관)

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | 조회 범위 확장 — `from: today-2` 적용 (공유 날짜 헬퍼 재사용) + `size` 10→20 상향 | frontend/dashboard | FE | 하 | [[portfolios-recent-disclosures-5d]] 카드 #1(BE 클램프)·#4(헬퍼) |
| 2 | "오늘" 전제 데이터 라벨 갱신 — StatCard "오늘 공시"→"최근 3일 공시", `aria-label="오늘 공시 통계"`, 빈 상태 문구 | frontend/dashboard | FE | 하 | #1 |
| 3 | Free 제한 배너 재정의 — 조건(`total_elements > 5`) 유지, 문구 "오늘 5건 조회 완료"→"최근 3일 기준 5건 표시" 계열 + `aria-label` 동기화 | frontend/dashboard | FE | 하 | #1 |
| 4 | 검증 — 네트워크 요청 from/to 확인(회귀: 미전달 시 전체 기간) + Free 시나리오(BE 5일 클램프 창 내 3일 요청 통과) + 헤더 주석 4종 갱신 | frontend/dashboard | FE | 하 | #2~#3 |

### DB / 마이그레이션 영향
- 없음 — Flyway 마이그레이션 불필요

### 외부 계약 영향
- DART/KRX/카카오/LLM: 없음
- 자체 REST: 없음 — 기존 `from`/`to` 파라미터 사용 방식만 변경. Free 티어 동작은 [[portfolios-recent-disclosures-5d]]의 BE 완화에 의존(본 Spec에서 계약 변경 없음)

### 리스크 & 법적 검토
- **배포 순서 강제 (핵심)**: [[portfolios-recent-disclosures-5d]] Wave 1(BE 클램프 완화) 미배포 상태에서 본 Spec만 배포하면 Free는 `from=today-2`가 오늘로 클램프되어 "최근 3일" 라벨과 실데이터 불일치 — 반드시 BE 선행
- **통계 오독**: "오늘 공시" → 3일 누계로 의미 변화. 라벨 명확화로 대응(사실 표시만, 투자 권유 표현 금지 §11.1). SentimentStatCard 집계도 3일 누계가 됨 — 라벨과 함께 이해되므로 허용
- 헤더 카피 "오늘의 내 공시 레이더": **유지 확정 (사용자 승인 2026-07-05)** — 데이터 범위 주장이 아닌 방문 맥락의 브랜드 카피. 데이터 라벨(카드 #2·#3)만 "최근 3일"로 갱신
- 접근성: aria-label 문구 동기화 필수(카드 #2·#3에 포함) — 시각 라벨과 스크린리더 정보 불일치 방지 (WCAG §6-5)

### 예상 wave 수
- **1 wave** (FE 단일 파일) — 단, 착수 조건: [[portfolios-recent-disclosures-5d]] Wave 1 완료 후. 같은 브랜치에서 연속 wave로 묶는 것도 가능(5d Wave 1 → 5d Wave 2 → 본 Spec wave)

### 판정
- **구현 가능 (Approved 전환 권장)** — 미확인 항목 해소 완료(FE 테스트 영향 없음 실측). 유일한 결정 대기였던 헤더 카피는 "유지"로 확정 제안
