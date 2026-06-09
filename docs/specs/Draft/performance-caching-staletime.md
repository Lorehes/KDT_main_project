---
type: spec
status: Draft
created: 2026-06-09
updated: 2026-06-09
---

# 성능 · 캐싱 보강 Spec (Caffeine + TanStack staleTime + size 제한)

> 상태: **Draft** (dc-plan 생성, 2회 코드 리뷰 종합)

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
