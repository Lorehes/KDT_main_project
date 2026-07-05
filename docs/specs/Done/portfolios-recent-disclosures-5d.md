---
type: spec
status: Done
created: 2026-07-05
updated: 2026-07-05
---

# /portfolios 종목별 최근 공시 — 최근 5일 범위 적용 Spec

> 상태: Approved → **Done** (2026-07-05, 구현·리뷰·테스트 게이트 통과)

## 배경 / 목적

- 최근 공시를 보여주는 3개 화면의 조회 범위가 화면 성격별로 다르다:
  - **dashboard** — 오늘(Asia/Seoul) 공시만 (`from=to=today`, [[dashboard-real-data]] R2)
  - **disclosures** — 보유 종목(또는 scope=all) 공시 전체, 날짜 필터 없음
  - **portfolios "종목별 최근 공시" 패널** — 날짜 필터 없이 최신 50건 조회 후 상위 5건 표시
- portfolios 패널은 "최근"이라는 라벨과 달리 실제로는 무기한 과거 공시까지 노출될 수 있다
  (공시가 뜸한 종목만 보유 시 수개월 전 공시가 "최근 공시"로 표시).
- 본 Spec: **portfolios 패널의 조회 범위를 "최근 5일"로 한정**하여 화면 의미와 데이터 정합을 맞춘다.
- 페르소나 연결: A(직장인 단타)·C(시니어) — "지금 봐야 할 최신 공시"만 간결하게.
- BM 티어: 전 티어 공통. **Free 티어 날짜 클램핑을 "오늘" → "최근 5일"로 완화** (사용자 확정 2026-07-05) — 건수 제한(일 5건, size≤5)은 유지.

## 요구사항

- [ ] `/portfolios` "🔔 종목별 최근 공시" 패널이 **최근 5일**(오늘 포함, Asia/Seoul 달력일 기준 `today-4 ~ today`) 공시만 표시
- [ ] 날짜 범위는 자정 경과 시 자동 갱신 (`useTodaySeoul` 훅 기반 파생 — 브라우저 장시간 열림 대응)
- [ ] 패널 헤더에 범위 명시 (예: "🔔 종목별 최근 공시" + "최근 5일" 보조 라벨) — 사용자가 범위를 오해하지 않도록
- [ ] 빈 상태 문구를 범위 반영으로 갱신: "아직 공시가 없습니다." → "최근 5일 내 공시가 없습니다." 수준
- [ ] 보유 종목 테이블 "최근 공시" 배지(`latestByStock`)도 동일 5일 윈도우 적용 (동일 쿼리 공유 — 아래 권장 방향 참조)
- [ ] **[BE] Free 티어 날짜 클램핑 완화**: `from=to=오늘` 강제 → 요청 `from`/`to`를 `[오늘-4일, 오늘]`(Asia/Seoul) 경계로 클램프. 미지정 시 기본값도 `[오늘-4일, 오늘]`. `page=0`·`size≤5` 강제는 유지(BM 일 5건 정책 불변)
- [ ] [BE] Free 클램핑 완화에 대한 통합 테스트 갱신 (기존 "오늘 강제" 검증 테스트 → 5일 경계 클램프 검증)
- [ ] 패널 라벨은 전 티어 단일 "최근 5일" (Free도 5일 조회 가능해지므로 라벨-데이터 불일치 해소)
- [ ] dashboard·disclosures **페이지(FE)는 변경 없음** — 단, BE 완화의 파급으로 Free 사용자의 disclosures 피드가 "오늘"→"최근 5일"로 넓어짐(아래 리스크 참조)

## 영향 범위 (조사 결과)

- 영향 레이어: **frontend(portfolios) + backend(disclosure)** — Free 클램핑 완화로 BE 변경 포함 (2026-07-05 확대)
- 영향 파일:
  - `frontend/src/app/(app)/portfolios/page.tsx` — `useDisclosures({scope:"portfolio", size:50, sort:"rcept_dt,desc"})` 호출에 `from`/`to` 추가 + 헤더/빈 상태 문구
  - (선택) `frontend/src/lib/hooks/useTodaySeoul.ts` — 재사용. 필요 시 N일 범위 파생 헬퍼 추가
  - `backend/src/main/java/com/dartcommons/disclosure/services/DisclosureQueryService.java` — Free 클램핑 블록(`fromDate=toDate=seoulToday`)을 5일 경계 클램프로 완화 + 헤더 주석 갱신
  - `backend/src/test/**` — Free 티어 날짜 강제 검증 테스트 갱신 (Testcontainers, 위치 확인 필요)
- 이미 지원되는 계약 (변경 불필요, 확인 완료):
  - `backend/.../disclosure/controllers/DisclosureController.java` — `from`/`to` `@DateTimeFormat(ISO.DATE)` 파라미터 기지원
  - `backend/.../disclosure/repositories/DisclosureRepository.java` — from/to nullable 필터 기지원
  - `frontend/src/lib/api/disclosures.ts` — `DisclosureListParams.from/to` 타입 기정의, queryKey에 params 포함 → 날짜 변경 시 캐시 자동 분리
- DB 변경: 없음 (Flyway 마이그레이션 불필요)
- 외부 계약: 없음 (DART/KRX/카카오/LLM 무관)

## 관련 패턴 / 과거 사례

- [[dashboard-real-data]] — R2 "오늘 필터"(`from=to=today`) + R3 "Free 티어 BE 강제(오늘+page0+5건)". 본 작업과 동일한 `from/to` 파라미터 패턴의 원형
- `frontend/src/lib/hooks/useTodaySeoul.ts` — Asia/Seoul 오늘 날짜 + 자정 자동 갱신 패턴. **5일 범위 계산은 이 훅의 반환값에서 파생할 것**
- 주의: `portfolios/page.tsx`의 기존 `getWeekRange()`(이번 주 공시 카드용)는 **브라우저 로컬 TZ 기준** — Asia/Seoul 미보장. 신규 5일 범위 계산에 이 함수 패턴을 복사하지 말 것 (동일 결함 전파 금지. 별도 수정 여부는 본 Spec 범위 밖 — 이슈 분리 가능)
- [[performance-caching-staletime]] — `useDisclosures` staleTime 60s 정책. 날짜 param 변경만으로 별도 캐시 무효화 불필요
- `backend/.../disclosure/repositories/DisclosureRepository.java` — `CAST(:fromDate AS date) IS NULL OR d.rcept_dt >= :fromDate` 패턴으로 from/to nullable 필터 완비 ([[be-api-blocking-bugs-fix]]에서 분리·안정화된 구조). BE 재사용만, 수정 금지
- 과거 회귀 사례 (dev-log, dashboard-real-data Wave 1): "오늘 필터 미전달 시 전체 기간 반환" — from/to 전달 누락이 조용한 전체 조회로 이어짐. 구현 후 네트워크 요청에 from/to 포함 여부 검증 필수

## 리스크 / 법적 검토

- **Free 티어 클램핑 완화 (사용자 확정 2026-07-05)**: 기존 `from=to=오늘` 강제([[dashboard-real-data]] R3)를 **최근 5일 경계 클램프로 완화**한다. 설계 원칙:
  - 요청에 `from`/`to`가 있으면 존중하되 `[오늘-4일, 오늘]` 범위로 경계 클램프 → dashboard가 보내는 `from=to=오늘`은 그대로 유지되어 **dashboard Free 동작 불변**
  - 미지정 시 기본 `[오늘-4일, 오늘]` → **파급**: Free 사용자의 disclosures 피드(날짜 미전달)가 "오늘"→"최근 5일"로 넓어짐. 의도된 완화로 간주하되 Tech Review에서 재확인
  - `page=0`·`size≤5` 강제는 유지 — BM "일 5건" 정책의 건수 축은 불변. 단 5일 범위에서 최대 5건이므로 실질 "표시 5건"에 가까워짐 → 통합기획서 §3.3 문구와의 정합은 [[DART공시통역_통합기획서]] 갱신 시 반영 (dc-doc-sync 대상)
- **dashboard 조회 범위**: 실제 구현은 오늘 하루(`from=to=today`)이며, "최근 3일"로의 확장은 **별도 Spec [[dashboard-recent-3days]]로 분리** (사용자 확정 2026-07-05) — 본 Spec은 portfolios + Free 클램핑 완화만 다룸
- 테이블 "최근 공시" 배지 의미 변화: 5일 내 공시가 없는 종목은 배지 "—" 표시로 바뀜 (현재는 50건 내 있으면 표시). 의도된 동작으로 간주하나 Tech Review에서 재확인
- 자본시장법(§11.1): 해당 없음 — 표시 범위 변경만. sentiment 배지는 기존 `SentimentBadge`(색+텍스트 병기, WCAG §6-5) 그대로 재사용
- 개인정보: 해당 없음 — 매수가/수량 미접촉

## 권장 구현 방향

- **단일 쿼리 유지 + from/to 추가** (권장):
  ```
  useDisclosures({ scope: "portfolio", size: 50, sort: "rcept_dt,desc", from: fiveDaysAgo, to: today })
  ```
  - `today = useTodaySeoul()`, `fiveDaysAgo = today 기준 -4일` (오늘 포함 5일). 파생 계산은 `useMemo(today)` 의존으로 자정 자동 갱신 승계
  - 패널(상위 5건 slice)과 테이블 배지(`latestByStock`)가 같은 응답을 공유 — 쿼리 1개 유지로 네트워크/캐시 비용 불변
- 대안(비권장): 패널용 5일 쿼리 + 배지용 무기한 쿼리 분리 — 배지 의미를 기존대로 유지할 수 있으나 API 호출 2배, "최근"의 의미가 화면 내에서 이원화됨
- 날짜 연산은 `Date` 직접 산술 대신 `useTodaySeoul` 반환 문자열(YYYY-MM-DD) 기반 파생 헬퍼로 — TZ 결함 재발 방지
- "최근 5일"은 **달력일 기준**(영업일 아님)으로 확정 제안 — DART 공시는 주말 접수 건도 존재하므로 달력일이 단순·안전. 영업일 기준 필요 시 Tech Review에서 재논의
- **[BE] Free 클램핑 완화 스케치** (`DisclosureQueryService` Free 블록):
  ```java
  LocalDate seoulToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
  LocalDate windowStart = seoulToday.minusDays(4);   // 오늘 포함 5일
  fromDate = (fromDate == null || fromDate.isBefore(windowStart)) ? windowStart : fromDate;
  toDate   = (toDate   == null || toDate.isAfter(seoulToday))     ? seoulToday  : toDate;
  page = 0;
  size = Math.min(size, 5);
  ```
  - 요청 범위를 창 안으로 "당기는" 클램프 — dashboard(`from=to=오늘`)는 결과 불변, 날짜 미지정 호출은 5일 창 기본 적용
  - `from > to` 역전 엣지(예: from=오늘+1) — 클램프 후에도 역전이면 빈 결과 반환이 자연 동작인지 테스트로 고정

## Tech Review (dc-tech-review · 2026-07-05)

### 아키텍처 분해
- 영향 레이어: backend(disclosure — services + 테스트) / frontend(portfolios + lib 헬퍼)
- 신규 vs 수정:
  - 신규: FE 날짜 파생 헬퍼 (예: `frontend/src/lib/date/recentRangeSeoul.ts` — 일수 파라미터화, [[dashboard-recent-3days]]와 공유 전제)
  - 수정: `DisclosureQueryService`(Free 블록), `portfolios/page.tsx`, BE 통합 테스트 2파일
- 도메인 경계: disclosure 도메인 내부 변경만 — 도메인 간 의존 변화 없음 (§3-2 정합)

### 작업 카드
| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | Free 클램핑 완화 — `from=to=오늘` 강제를 `[오늘-4, 오늘]` 경계 클램프로 변경 + 헤더 주석 4종 갱신 | backend/disclosure | BE | 중 | - |
| 2 | `DisclosureQueryServiceIntegrationTest` 재작성 — "어제 제외" → "어제 포함 + 6일 전 제외 + from>to 역전 엣지" 3케이스, size≤5 클램핑 테스트 유지 | backend/test | BE | 중 | #1 |
| 3 | `DisclosureControllerTest` 픽스처 조정 — "FREE 과거 날짜 0건" 전제 3곳(L146·243·351): 삽입 날짜를 5일 창 밖으로 이동 또는 어서션 수정 | backend/test | BE | 하 | #1 |
| 4 | FE 날짜 파생 헬퍼 — `useTodaySeoul` 반환 문자열(YYYY-MM-DD) 기반 N일 전 계산 (Date 산술·로컬 TZ 금지) | frontend/lib | FE | 하 | - |
| 5 | portfolios 패널 적용 — `useDisclosures`에 from/to 추가 + "최근 5일" 라벨 + 빈 상태 문구 + 헤더 주석 갱신 | frontend/portfolios | FE | 하 | #4 |
| 6 | 검증 — 네트워크 요청에 from/to 포함 확인(과거 회귀: 미전달 시 전체 기간 반환) + 테이블 배지 5일 윈도우 동작 확인 | frontend | FE | 하 | #5 |

### DB / 마이그레이션 영향
- 없음 — Flyway 마이그레이션 불필요 (rcept_dt 필터는 기존 쿼리 재사용, 인덱스 변경 없음)

### 외부 계약 영향
- DART/KRX/카카오/LLM: 없음
- 자체 REST 계약: 파라미터 시그니처 불변. **Free 티어 응답 의미 변화**(날짜 미지정 시 오늘 → 최근 5일) — `api_spec.md`·통합기획서 §3.3 "일 5건" 서술에 날짜 창 각주 필요 → 구현 후 `/dc-doc-sync` 대상
- 파급 확인: dashboard(`from=to=오늘` 명시 전송)는 경계 클램프 설계상 동작 불변 — 클램프가 "범위 축소"가 아닌 "창 밖 값 당기기"임을 테스트 #2로 고정

### 리스크 & 법적 검토
- **BM 비가역성**: Free 혜택 확대(오늘→5일)는 배포 후 회수 시 사용자 반발 소지 — 사용자 확정(2026-07-05) 근거로 진행, 통합기획서 갱신으로 SSOT 고정
- **파급**: Free 사용자의 disclosures 피드도 5일로 넓어짐 (날짜 미지정 호출) — 의도된 완화로 승인됨, 릴리스 노트에 명시 권장
- 자본시장법 §11.1: 해당 없음 — 조회 범위 변경만, 신규 표현 없음. SentimentBadge(색+텍스트) 재사용으로 WCAG §6-5 유지
- 기술 엣지: `from > to` 역전(클램프 후 포함) 시 빈 결과 — 테스트 #2에 케이스 포함

### 예상 wave 수
- **2 waves**:
  - Wave 1 (BE): 카드 #1~#3 — Free 클램핑 완화 + 테스트. [[dashboard-recent-3days]]의 선행 조건이므로 독립 배포 가능하게 분리
  - Wave 2 (FE): 카드 #4~#6 — portfolios 적용 + 검증

### 판정
- **구현 가능 (Approved 전환 권장)** — 요구사항 모호점 없음, 미확인 항목 없음. 오픈 이슈였던 Free 정책·라벨 분기·dashboard 분리 모두 사용자 확정 반영 완료

### 구현 후 Follow-up (dc-review-code 2026-07-05)
- **[P2, 미해결] 날짜 창 상수 BE/FE 3곳 리터럴 중복**: BE `FREE_WINDOW_DAYS=5` ↔ FE `RECENT_DISCLOSURE_DAYS=5`·`RECENT_FEED_DAYS=3` 결합이 헤더 주석으로만 문서화됨. 장기: 티어 정책(날짜 창·건수 상한)을 API(`/tiers` 또는 config 응답)로 노출해 FE 하드코딩 제거 — 별도 Spec 후보
- [P3, 의도적 미수정] Free 클램프 양방향화 제안 기각: 완전 과거 범위 요청(from·to 모두 창 밖)에 창 데이터를 반환하면 사용자가 요청한 기간과 다른 데이터를 보여주게 됨 — 빈 결과가 정직한 동작. from>to 역전 빈 결과는 통합 테스트로 고정됨
- [반영 완료] 5일 창 경계 픽스처(today-4 포함/today-5 제외) 테스트 강화, dashboard useMemo 통일, dev-log session:null 관례 준수
