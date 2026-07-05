---
type: spec
status: Done
created: 2026-07-05
updated: 2026-07-05
---

# 티어 정책 단일 소스화 — 날짜 창·건수 상한 config/API 노출 Spec

> 상태: Approved → **Done** (2026-07-05, 구현·5페르소나리뷰·실앱검증 통과)

## 배경 / 목적

- Free 티어 공시 조회 날짜 창 값이 강제 수단 없이 3곳 리터럴로 흩어져 있음:
  | 위치 | 상수 | 값 |
  |------|------|----|
  | `DisclosureQueryService.java:57` | `FREE_WINDOW_DAYS` | 5 |
  | `portfolios/page.tsx:32` | `RECENT_DISCLOSURE_DAYS` | 5 |
  | `dashboard/page.tsx:30` | `RECENT_FEED_DAYS` | 3 |
- 결합은 헤더 주석으로만 문서화 → BE가 창을 축소하면 FE 두 상수를 사람이 함께 고치지 않는 한 **Free 라벨-실데이터 불일치**가 조용히 발생([[portfolios-recent-disclosures-5d]] Follow-up).
- 티어 정책(날짜 창·건수 상한)이 늘어날수록 이 중복이 확산. 단일 소스화로 회귀를 구조적으로 차단.
- BM 티어: 정책 자체가 티어(Free/Pro/Premium)별 차등 대상.

## 요구사항

- [ ] Free 티어 조회 날짜 창·건수 상한을 **백엔드 단일 소스**에서 관리
- [ ] FE가 하드코딩 상수 대신 **API 응답에서 파생**(라벨 "최근 N일"도 응답값 기반)
- [ ] (단기 안전망) BE↔FE 창 값 불변식(`RECENT_FEED_DAYS ≤ RECENT_DISCLOSURE_DAYS ≤ FREE_WINDOW_DAYS`) 회귀 테스트 — 상수 이탈 시 빌드 실패
- [ ] 기존 `/api/v1/pricing/plans` 계약 하위호환 유지(필드 추가만, 제거/변경 금지)

## 영향 범위 (조사 결과)

- 영향 레이어: **backend(user/shared) + frontend**
- 기존 자산(재사용 대상, 확인 완료):
  - `backend/.../user/controllers/PricingController.java` — `GET /api/v1/pricing/plans` 이미 존재
  - `backend/.../shared/config/PricingProperties.java` — `Plan` record(`tier`, `monthlyFreeQuota` 등) `application.yml` 바인딩. **날짜 창 필드 추가 지점**
  - `frontend/src/lib/api/pricing.ts` — `usePricingPlans()` 훅·`PricingPlan` 타입 이미 존재. FE 파생 진입점
- 신규/수정:
  - `PricingProperties.Plan`에 `recentWindowDays`(또는 유사) 필드 추가 + `application.yml` 값 정의
  - `PlanResponse` + FE `PricingPlan` 타입에 필드 반영
  - `DisclosureQueryService.FREE_WINDOW_DAYS` → PricingProperties 주입값 참조로 교체
  - `portfolios/page.tsx`·`dashboard/page.tsx` 상수 → API 파생값(또는 서버 주입)으로 교체
- DB 변경: 없음(config 기반). 외부 계약: `/pricing/plans` 응답 필드 추가(하위호환)

## 관련 패턴 / 과거 사례

- `[[mvp-missing-endpoints]]`(Done) — pricing plans 엔드포인트 도입 이력. 본 Spec은 그 응답 스키마 확장
- `[[portfolios-recent-disclosures-5d]]`(Done) — 상수 3곳을 만든 원 작업. Follow-up 섹션에 본 이슈 명시
- `PricingProperties`의 `@ConfigurationProperties` + record 바인딩 패턴 — 신규 필드도 동일 방식

## 리스크 / 법적 검토

- **오버엔지니어링 경계**: 현재 티어 정책 축이 적음(날짜 창 1종 + 건수 1종). API 전면화가 과할 수 있음 → Tech Review에서 **단기(회귀 어서션만) vs 장기(API 노출)** 범위를 먼저 확정. 단기만으로도 "조용한 불일치" 리스크는 상당 부분 차단됨
- FE 파생 전환 시 로딩 상태(플랜 응답 도착 전)의 날짜 창 기본값 처리 필요 — SSR/초기 렌더에서 창 값이 없을 때 빈 화면/깜빡임 방지책 필요
- `/pricing/plans` 응답 변경 시 기존 `/pricing` 페이지 렌더 회귀 확인(필드 추가만이라 하위호환이나 확인 필요)
- 자본시장법/개인정보: 해당 없음 — 정책 메타데이터 노출

## 권장 구현 방향

- **2단계 접근(권장)**:
  - **Phase 1 (즉시, 저비용)**: BE 통합 테스트 + FE 유닛에 불변식 어서션 추가 — 상수는 그대로 두되 이탈 시 빌드가 깨지게. 시연 리스크 없이 회귀 차단
  - **Phase 2 (선택, 후속)**: `PricingProperties.Plan`에 날짜 창 필드 추가 → `/pricing/plans` 노출 → FE 파생. 티어 정책 축이 늘어나는 시점에 착수
- 대안: 처음부터 API 전면화 — 정책 축이 적은 현시점엔 비용 대비 효과 낮음. Phase 분리로 리스크·비용 통제
- 확정 필요(Tech Review): Phase 1만으로 종결할지, Phase 2까지 한 사이클로 볼지

## Tech Review (dc-tech-review · 2026-07-05)

### 아키텍처 분해
- 영향 레이어: **backend(disclosure, shared/config) + frontend**
- 재사용 자산(확인 완료): `PricingProperties.Plan` record(tier·price·features·`monthlyFreeQuota`) + `application.yml pricing.plans`(FREE `monthly-free-quota: 5`) + `PricingController` `GET /pricing/plans` + FE `usePricingPlans()`/`PricingPlan`
- 도메인 경계: `DisclosureQueryService`(disclosure)가 `PricingProperties`(shared/config)를 주입 = **도메인 → shared** 정상 방향(§3-2). 이미 shared(PageResponse·Tier·enums) 의존 중이라 신규 위반 아님

### ⚠️ 결합 성격 재분류 (구현 정확도 핵심)
상수 3개는 "같은 값"이 아니다 — **두 종류**로 나뉜다:
- `FREE_WINDOW_DAYS`(BE, 5) = **Free 티어 조회 클램프 상한**(정책값)
- `RECENT_DISCLOSURE_DAYS`(portfolios, 5) = Free 창을 **그대로 노출**하는 표시 창 → `== FREE_WINDOW_DAYS` 여야 Free 라벨-데이터 정합
- `RECENT_FEED_DAYS`(dashboard, 3) = 순수 **UI 표시 선택**(3일) → Free 창과 무관, `≤ FREE_WINDOW_DAYS` 제약만 필요
- 불변식: `RECENT_FEED_DAYS(3) ≤ RECENT_DISCLOSURE_DAYS(5) == FREE_WINDOW_DAYS(5)`
- **함의**: API 노출(Phase 2)의 직접 대체 대상은 `RECENT_DISCLOSURE_DAYS`(=창)와 BE 상수뿐. `RECENT_FEED_DAYS`는 API 파생 대상이 아니라 "창 이하" 검증만 받는 독립 표시값

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 | Phase |
|---|------|--------|------|--------|--------|-------|
| 1 | [BE] `DisclosureQueryServiceIntegrationTest`에 FREE 창값 계약 어서션 명시화 — 현 5일 경계가 상수와 일치함을 문서화(기존 경계 테스트 강화) | backend/test | BE | 하 | - | 1 |
| 2 | [FE] Vitest — `RECENT_FEED_DAYS ≤ RECENT_DISCLOSURE_DAYS` 불변식 + 두 값이 문서화된 FREE 창(5) 이하인지 어서션 | frontend/lib | FE | 하 | - | 1 |
| 3 | [BE] `PricingProperties.Plan`에 `recentWindowDays` 필드 추가(FREE=5, PRO/PREMIUM=0=무제한) + `application.yml` + `PlanResponse` 반영 | backend/shared | BE | 중 | - | 2 |
| 4 | [BE] `DisclosureQueryService.FREE_WINDOW_DAYS` 상수 → `PricingProperties` 주입값 참조로 교체(@RequiredArgsConstructor 필드 추가) | backend/disclosure | BE | 중 | #3 | 2 |
| 5 | [FE] `pricing.ts PricingPlan`에 `recent_window_days` 추가 + `portfolios/page.tsx`가 `usePricingPlans()` FREE 창 파생(로딩 전 기본값 폴백). dashboard는 독립 표시상수 유지하되 "≤ 창" 검증 | frontend | FE | 중 | #3 | 2 |
| 6 | [검증] BE 통합 테스트(주입값 기반 클램프 동작) + FE 회귀(로딩 폴백·라벨 정합·/pricing 페이지) | 양쪽 | BE+FE | 중 | #4,#5 | 2 |

### DB / 마이그레이션 영향
- 없음 — 정책값은 `application.yml`(config) 기반. Flyway 불필요. (PricingProperties 주석: 가격 변경 빈도 낮아 DB 대신 yml 선택 — 날짜 창도 동일 성격)

### 외부 계약 영향
- `GET /api/v1/pricing/plans` 응답에 `recent_window_days` **필드 추가(하위호환 — additive)**. 기존 소비처(/pricing 페이지) 무영향
- DART/KRX/카카오/LLM: 없음

### 리스크 & 법적 검토
- **Phase 1의 크로스-런타임 강제 한계(오버클레임 금지)**: BE(Java)·FE(TS)는 분리 빌드라 **컴파일타임 단일 검증 불가**. Phase 1은 부분 보호(FE 내부 불변식 + BE 값 문서화 테스트)일 뿐, "BE가 창을 3으로 낮췄는데 FE는 5로 라벨" 유형을 완전 차단하지 못함. **진짜 단일 소스는 Phase 2(API 파생)** — Phase 1을 "완전 해결"로 보고하지 말 것
- **오버엔지니어링 경계**: 현재 정책 축이 적음(창 1 + 건수 1). Phase 2 착수는 티어 정책 축이 늘어나는 시점에 재평가 권장 — Approved 시 **Phase 1만 vs Phase 1+2** 범위를 먼저 확정
- **FE 로딩 폴백(Phase 2)**: `/pricing/plans` 응답 도착 전 창 값 미정 → 초기 렌더 빈 쿼리/깜빡임 방지 기본값 필요(SSR·최초 로드). staleTime 60s여도 최초엔 undefined
- **의미 혼동 방지**: `recentWindowDays`(날짜 축)와 `monthlyFreeQuota`(건수 축, "일 5건")는 별개 정책 — 필드·주석에서 구분. 클램프의 `size≤5`(건수)와 창(날짜)을 섞지 말 것
- 자본시장법/개인정보: 해당 없음 — 정책 메타데이터

### 예상 wave 수
- **2 waves(Phase 1+2 연속 — 사용자 확정 2026-07-05, 옵션 b)**:
  - Wave 1 = Phase 1(카드 #1~#2): 즉시·저비용 회귀 안전망. 시연/배포 리스크 없음
  - Wave 2 = Phase 2(카드 #3~#6): API 단일 소스화. **본 사이클에 연속 진행** — Wave 1 완료 후 곧바로 착수
  - 순서 근거: Phase 1 어서션이 Phase 2로 상수를 제거하기 전 "현재 값 계약"을 고정 → Phase 2 리팩터의 회귀 그물망 역할

### 판정
- **구현 가능 (Approved 전환 확정 · 범위 = Phase 1+2 옵션 b, 사용자 확정 2026-07-05)** — Phase 1(회귀 안전망) → Phase 2(API 단일 소스화)를 한 사이클로 진행. 크로스-런타임 강제 한계(리스크 섹션)는 Phase 2의 API 파생으로 최종 해소됨. Phase 1 어서션은 Phase 2 착수 후에도 유지(파생 전환 자체의 회귀 검증).
