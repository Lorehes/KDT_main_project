---
type: spec
status: Done
created: 2026-06-09
updated: 2026-06-23
---

# 성능 · 캐싱 보강 Spec (Caffeine + TanStack staleTime + size 제한)

> 상태: Draft → Approved (2026-06-23, dc-tech-review 승인 — R1·R3 정정, R5·R10 기완료 확인, R6 드롭) → **Done** (2026-06-23, 구현 완료 — 149/149 BE + 30/30 FE 통과, 28936c1)

## 배경 / 목적

`FE-BE정합성수정` 으로 공시·분석·포트폴리오 API 가 라이브에 올라왔으나, **매 요청 portfolios SELECT + TanStack Query staleTime 0 + size 상한 없음** 으로 부하 시 DB 핫스팟·불필요한 재요청·OOM 위험이 있다. 본 Spec 은 캐싱 전략과 조회 제한을 일괄 적용한다.

- **현황**: `DisclosureQueryService.resolveStockCodes()` 가 매 피드 요청마다 portfolios SELECT, TanStack Query 훅 다수에 `staleTime` 미설정, `DisclosureController.list().size` 상한 없음
- **목표**: 초당 100 요청 기준 portfolios 핫스팟 제거 + 포커스 복귀 재요청 90% 감소 + DoS size 가드
- **BM 연관**: 전 티어 — 응답 속도 개선 + 인프라 비용 절감

---

## 요구사항

### BE 캐싱

- [ ] **R1** `DisclosureQueryService.resolveStockCodes(userId)` 에 `@Cacheable(value="portfolioStockCodes", key="#userId")` 적용. Caffeine TTL 5분
- [ ] **R2** `PortfolioService` create/update/delete 메서드에 `@CacheEvict(value="portfolioStockCodes", key="#userId")` 추가 — 포트폴리오 변경 즉시 캐시 무효화
- [ ] **R3** `AnalysisQueryService.getByDisclosureId(id)` 에 `@Cacheable(value="analysisResult", key="#disclosureId")` 적용. Caffeine TTL 10분. 재분석 시 `@CacheEvict` (현재 재분석 경로 미존재 — `AnalysisOrchestrator.analyze` 완료 시점에 evict 추가)
- [ ] **R4** `application.yml` Caffeine 캐시 설정 추가 — `spring.cache.caffeine.spec=maximumSize=10000,expireAfterWrite=5m` 기본값 + 캐시별 override

### BE 조회 제한

- [ ] **R5** `DisclosureController.list().size` 에 `@Max(100)` + `@Min(1)` Bean Validation 적용. 서비스 진입부 `Math.min(size, 100)` 이중 방어
- [ ] **R6** `findByDisclosureIdIn(...)` 호출 전 `ids.size() <= 100` assert — size cap 위반 시 IllegalArgumentException. 배치 호출은 별도 chunk 분할 메서드 분리

### FE staleTime / cacheTime

- [ ] **R7** TanStack Query 기본 `staleTime` 조정 — `app/providers.tsx` 의 `staleTime: 60_000` → 항목별 override 로 변경
- [ ] **R8** 훅별 staleTime 명시:
  - `useDisclosures`: 60초
  - `useDisclosure(id)`: 5분 (거의 불변)
  - `useDisclosureAnalysis(id)`: 5분
  - `useNotifications`: 30초
  - `usePortfolios`: 2분
  - `useNotificationSettings`: 5분
- [ ] **R9** `refetchOnWindowFocus` 항목별 정책 — 공시 상세/분석은 false, 알림/포트폴리오는 true

### 인덱스 검토

- [ ] **R10** `(stock_code, rcept_dt DESC)` 복합 인덱스 추가 — `V19__add_disclosure_compound_index.sql`. 종목별 최신 공시 쿼리 성능 향상
- [ ] **R11** 본 Spec 머지 후 `EXPLAIN ANALYZE` 로 공시 피드 쿼리 플랜 검증 — seq scan 발생 여부 확인

---

## 영향 범위

- **영향 레이어**: backend (`disclosure/services`, `analysis/services`, `user/services`, `application.yml`) + frontend (`lib/api/*`, `app/providers.tsx`)
- **DB 변경**: `V19__add_disclosure_compound_index.sql` (인덱스 추가)
- **외부 계약**: 없음 — 응답 스키마/엔드포인트 변경 없음

### 수정 파일

| 파일 | 변경 내용 |
|------|------|
| `backend/.../disclosure/services/DisclosureQueryService.java` | R1 @Cacheable |
| `backend/.../user/services/PortfolioService.java` | R2 @CacheEvict |
| `backend/.../analysis/services/AnalysisQueryService.java` | R3 @Cacheable |
| `backend/.../analysis/services/AnalysisOrchestrator.java` | R3 evict 시점 추가 |
| `backend/src/main/resources/application.yml` | R4 Caffeine 설정 |
| `backend/.../disclosure/controllers/DisclosureController.java` | R5 @Max/@Min |
| `backend/.../analysis/repositories/AnalysisResultRepository.java` | R6 size cap assert |
| `backend/src/main/resources/db/migration/V19__add_disclosure_compound_index.sql` (신규) | R10 |
| `frontend/src/app/providers.tsx` | R7 default 조정 |
| `frontend/src/lib/api/disclosures.ts` | R8 항목별 staleTime |
| `frontend/src/lib/api/notifications.ts` | R8 |
| `frontend/src/lib/api/portfolios.ts` | R8 |

---

## 관련 패턴 / 과거 사례

- `notification-retry-job` (Done) — `findRetryTargets(Pageable.of(100))` 배치 size 제한 패턴
- `disclosure-collection-pipeline` (Done) — DART 폴링 캐시 (`lastPolledDate` system_configs)
- CLAUDE.md §2 — Cache: Caffeine + Spring Cache 명시
- 통합기획서 §5 (아키텍처) — 캐싱 계층 위치

---

## 리스크 / 법적 검토

| 리스크 | 대응 |
|------|------|
| `@Cacheable` 도입 후 stale 데이터 응답 | TTL 5~10분 + write 경로 evict 보장. 핵심 read 경로(공시 피드)는 60초 TTL 검토 |
| Caffeine 메모리 점유 | `maximumSize=10000` per cache. 모니터링 후 조정 |
| staleTime 증가로 알림 지연 | `useNotifications` 30초 + `refetchOnWindowFocus=true` 로 즉시성 보장 |
| size 상한 100 으로 기존 클라이언트 호환 깨짐 | FE 현재 page size 20 — 호환성 영향 없음. API 문서에 명시 |
| 복합 인덱스 추가로 INSERT 비용 증가 | 공시 INSERT 는 분당 < 1k 행 수준 — 인덱스 비용 미미 |

---

## 권장 구현 방향

- Wave 1 (BE 캐싱): R1·R2·R3·R4 — 공시 피드 부하 즉시 감소
- Wave 2 (조회 제한): R5·R6 — DoS 가드 ([[security-hardening-mvp]] R9 와 통합 머지)
- Wave 3 (FE staleTime): R7·R8·R9 — TanStack Query 전체 정합
- Wave 4 (인덱스): R10·R11 — `EXPLAIN ANALYZE` 베이스라인 측정 후 적용
- [[be-api-blocking-bugs-fix]] R2 (ORDER BY 추가) 머지 이후 R10 인덱스 효율 검증

<!-- Tech Review 섹션은 /dc-tech-review 가 추가 -->

## Tech Review (dc-tech-review · 2026-06-23)

> **검토 결론: 조건부 승인 (Spec 본문 요구사항 5건 정정 필요).**
> 실제 BE/FE 코드를 직접 대조한 결과, **R5·R10 은 이미 구현 완료**, **R1·R3·R6 은 현재 코드 구조상 작성된 그대로는 동작 불가/오류**다.
> 아래 정정 반영 후 구현 진입 권장. (BE 코드 = SSOT 원칙으로 Spec "현황" 서술 일부가 stale.)

### 코드 대조 결과 — Spec 전제 검증

| Spec 항목 | Spec 전제 | 실제 코드 | 판정 |
|---|---|---|---|
| R1 | `resolveStockCodes(userId)` 에 `@Cacheable` | `private List<String> resolveStockCodes(Long userId, String scope, String stockCode)` — **private**, 시그니처 3-param, disclosure 도메인 | ❌ **동작 불가** (재타겟 필요) |
| R3 | `getByDisclosureId(id)` `key="#disclosureId"` | `getByDisclosureId(Long disclosureId, Tier tier)` — **티어별 필드 차등** DTO 반환 | ❌ **교차티어 캐시오염 버그** |
| R5 | `size` 상한 없음 → `@Max(100)`+`@Min(1)` 추가 | `DisclosureController:60` 이미 `@Positive @Max(100)` 적용됨 | ✅ **이미 완료** (잔여 0~경미) |
| R6 | `findByDisclosureIdIn` 에 size assert | Spring Data **인터페이스 derived 메서드** — 바디 작성 불가. ids 는 이미 page size(≤100) 로 bounded | ❌ **위치 부적합 + 중복** |
| R10 | `(stock_code, rcept_dt DESC)` 인덱스 신규 추가 (V19) | `V4:26 idx_disclosures_stock ON disclosures (stock_code, rcept_dt DESC)` **이미 존재** | ❌ **중복 인덱스** (드롭) |
| 마이그레이션 | `V19__...` 제안 | 현재 최신 = **V20** 적용됨 (V19 슬롯 결번) | ⚠ V19 사용 시 Flyway out-of-order |
| 캐시 인프라 | R4 가 spec 문자열만 추가 | deps(caffeine, starter-cache) + `application.yml cache.type=caffeine` 존재하나 **`@EnableCaching` 부재** | ⚠ **선행 차단 항목 누락** |

### 아키텍처 분해

- **영향 레이어**: backend(`user/services` ← R1 캐시 재타겟, `analysis/services` ← R3, `shared/config` ← @EnableCaching 신규, `disclosure/controllers` ← R5 잔여) / frontend(`lib/api/disclosures.ts` 주작업, `notifications.ts`·`portfolios.ts` 값 조정, `app/providers.tsx`)
- **신규**: `shared/config/CacheConfig.java`(@EnableCaching + CaffeineCacheManager bean)
- **수정**: `UserStockCodesProviderImpl`(R1 재타겟), `PortfolioService`(R2 evict), `AnalysisQueryService`(R3 재설계), FE 훅 staleTime
- **드롭**: R6, R10, V19 마이그레이션

### 작업 카드

| # | 작업 | 레이어 | 담당 | 난이도 | 의존성 |
|---|------|--------|------|--------|--------|
| 1 | **(선행)** `CacheConfig` + `@EnableCaching` + CaffeineCacheManager(캐시별 spec) 구성 | backend/shared | BE | 중 | - |
| 2 | R1 재타겟: `UserStockCodesProviderImpl.getStockCodes(userId)` 에 `@Cacheable("portfolioStockCodes", key="#userId")` | backend/user | BE | 하 | #1 |
| 3 | R2: `PortfolioService` create/delete 에 `@CacheEvict("portfolioStockCodes", key="#userId")` (update 는 stock_code 불변 → evict 불필요) | backend/user | BE | 하 | #2 |
| 4 | R3 재설계: tier 무관 엔티티 캐시 — `findByDisclosureId` 결과를 `@Cacheable("analysisResult", key="#disclosureId")` 분리 메서드로, 티어 projection 은 캐시 밖에서 수행 (또는 `key="#disclosureId + ':' + #tier"`) | backend/analysis | BE | 중 | #1 |
| 5 | R4: `application.yml` 캐시별 spec override (portfolioStockCodes 5m, analysisResult 10m, maximumSize) | backend/resources | BE | 하 | #1 |
| 6 | R5 잔여: 서비스 진입부 `Math.min(size,100)` 이중 방어 (컨트롤러 `@Max(100)` 이미 존재 — 선택적) | backend/disclosure | BE | 하 | - |
| 7 | R8/R9: `disclosures.ts` 3 훅(`useDisclosures` 60s / `useDisclosure` 5m / `useDisclosureAnalysis` 5m + `refetchOnWindowFocus:false`) 추가 | frontend/lib | FE | 하 | - |
| 8 | R8 값정합: `notifications.ts` useNotificationSettings 60s→5m, `portfolios.ts` 60s→2m (현재값 ≠ Spec 목표) | frontend/lib | FE | 하 | - |
| 9 | R11: 머지 후 `EXPLAIN ANALYZE` 로 `idx_disclosures_stock` 활용 확인 (신규 인덱스 불필요, 검증만) | backend/DB | BE | 하 | - |

> 카드 4 권장안: `AnalysisQueryService` 에 `@Cacheable` 엔티티 조회 메서드를 만들고 self-invocation 회피를 위해 별도 빈(또는 repository 레이어 캐시) 분리. 티어 projection(`AnalysisResponse.from(result, tier)`) 은 절대 캐시 대상에 포함 금지.

### DB / 마이그레이션 영향

- **신규 마이그레이션 불필요.** R10 의 `(stock_code, rcept_dt DESC)` 는 `V4__create_disclosures.sql:26 idx_disclosures_stock` 로 **이미 존재**. V19 추가 시 중복 인덱스 + (V20 이미 적용으로) Flyway 순서 충돌.
- R11 은 마이그레이션이 아닌 **검증 작업** — 기존 인덱스가 `findFilteredByStocks`(stock_code IN + ORDER BY rceptDt DESC) 플랜에서 쓰이는지 `EXPLAIN ANALYZE` 확인.
- 향후 정말 새 인덱스가 필요해지면 파일명은 **`V21__`** 부터 (V20 이 최신).

### 외부 계약 영향

- 없음. 응답 스키마·엔드포인트·DART/KRX/카카오/LLM 프롬프트 변경 없음. 순수 내부 캐싱/검증/FE 페칭 정책.

### 리스크 & 법적 검토

- **(P1·정확성) R3 교차티어 캐시오염**: `getByDisclosureId(id, tier)` 는 티어별 필드 차등(`@JsonInclude(NON_NULL)`) DTO 를 반환. `key="#disclosureId"` 단독 캐시 시 **Free 사용자의 축소 응답이 Premium 에게(또는 역방향) 제공**되어 유료 필드 누락/과다노출 양방향 버그. → 엔티티 레벨 캐시(티어 무관) 후 projection 분리, 또는 key 에 tier 포함 **필수**.
- **(차단) @EnableCaching 부재**: 미구성 시 R1~R3 `@Cacheable` 가 **조용히 무시**됨(no-op) — 부하 감소 효과 0. 카드 #1 선행 필수.
- **(설계) R1 self-invocation**: 원안의 private `resolveStockCodes` 는 동일 빈 내부 호출 + private 로 Spring AOP 프록시 미적용. user 도메인 `UserStockCodesProviderImpl.getStockCodes` 로 재타겟 시 cross-bean 프록시 경로라 정상 동작 + @CacheEvict(R2)와 **동일 도메인**으로 캐시 일관성 응집. (단 scope=all·명시 stockCode 경로는 캐시 우회 — 의도된 정상 동작.)
- **(저위험) R3 evict 시점**: 현재 분석은 write-once(`uq_analysis_disclosure` UNIQUE, 재분석 경로 미존재). 캐시 entry 가 덮어써지는 상황이 없어 evict 는 **재분석 기능 도입 시점까지 보류** 가능. Stage 3+ 도입 시 `Stage2Analyzer`/재분석 진입점에 evict 추가.
- 자본시장법/개인정보: 캐시 대상에 매수가·수량(복호화 PII) 포함 없음 — `portfolioStockCodes` 는 종목코드 List, `analysisResult` 는 분석 메타. 금융 PII 캐시 누출 없음. ✅
- **stale 데이터**: TTL(portfolioStockCodes 5m) 동안 포트폴리오 변경 미반영 우려는 R2 evict 로 즉시 무효화. analysisResult 10m 은 write-once 라 무해.

### 예상 wave 수

원안 4-wave → **3-wave 재조정** (R6·R10 드롭, R5 잔여 경미):
- **Wave 1 (BE 캐싱 인프라+적용)**: #1 → #2 → #3 → #4 → #5 (#1 이 모든 캐시의 선행)
- **Wave 2 (FE staleTime 정합)**: #7 → #8 (BE 독립, 병렬 가능)
- **Wave 3 (검증·잔여)**: #6(선택) + #9 `EXPLAIN ANALYZE` 베이스라인
- [[security-hardening-mvp]] R9(DoS size 가드)와 R5 잔여는 사실상 이미 컨트롤러에서 충족 — 통합 머지 시 중복 확인만.

### 결정 사항 (사용자 확정 · 2026-06-23)

1. **R3 캐시 키** → **엔티티 캐시 + projection 분리** 채택. `AnalysisResult` 엔티티(티어 무관)를 `@Cacheable("analysisResult", key="#disclosureId")` 로 캐시하고, `AnalysisResponse.from(result, tier)` projection 은 캐시 밖에서 매 요청 수행. (카드 #4 = 엔티티 캐시 방향 확정.)
2. **R5/R6** → **서비스 이중방어 추가** 채택. 컨트롤러 `@Max(100)` 유지 + 서비스 진입부 `Math.min(size,100)` 이중 방어(카드 #6) 포함. **R6(repository assert)는 드롭** — 인터페이스 메서드라 작성 불가 + ids 이미 page size 로 bounded.
