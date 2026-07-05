---
type: moc
status: active
created: 2026-06-23
updated: 2026-07-05
---

# 이슈 (Issues)

> 기술 부채, 미구성 인프라, 개선 필요 사항 트래킹.
> 해결 우선순위 확정 후 `/dc-tech-review <slug>`로 Spec 승격.

## 목록

| 이슈 | 상태 | 발견일 | 우선순위 |
|------|------|--------|----------|
| [[e2e-portfolio-keyboard-nav-failing]] — E2E 종목검색 키보드 네비 (a)(b) 실패 (HEAD 기존 결함) | **Open** | 2026-07-05 | P2 |
| [[week-disclosure-count-color-only]] — /portfolios "이번 주 공시" 3/2/0 색상 단독 구분 (§6-5) | **Closed (→ Spec: week-sentiment-count-a11y)** | 2026-07-05 | P2 |
| [[disclosure-card-raw-rcept-dt-format]] — DisclosureCard 날짜 원시 포맷(YYYYMMDD) 노출 | **Closed (→ Spec: disclosure-date-format-unify)** | 2026-07-05 | P3 |
| [[tier-date-window-constant-coupling]] — 티어 날짜 창 상수 BE/FE 3곳 중복 (강제 없음) | **Closed (→ Spec: tier-policy-config-api)** | 2026-07-05 | P3 |
| [[fe-unit-test-infra]] — FE Vitest + Testing Library 미구성 | **Closed** | 2026-06-23 | P1 |
| [[krx-anomaly-filter-test-coverage]] — KRX 이상치 필터(isValidPrice·±50%) 테스트 미작성 | **Closed** | 2026-06-24 | P2 |
| [[dashboard-today-midnight-staleness]] — 대시보드 자정 날짜 미갱신 | **Closed** | 2026-06-22 | P2 |
| [[disclosure-free-tier-enforcement-test]] — Free 티어 날짜·사이즈 강제 통합 테스트 미작성 | **Closed** | 2026-06-22 | P2 |
| [[user-profile-v22-integration-test]] — PATCH /users/me V22 통합 테스트 미작성 | **Closed** | 2026-06-22 | P2 |
| [[portfolio-review-cache-parsesafe-test]] — PortfolioService 캐시·parseSafe 테스트 미작성 | **Closed** | 2026-06-22 | P2 |
| [[topbar-settings-frontend-tech-debt]] — TopBar·Settings·PublicMobileMenu 프론트 기술부채 10항목 | **Closed** | 2026-06-22 | P2 |
| [[public-navbar-aria-labels]] — PublicNavbar 비로그인 CTA aria-label 미적용 | **Closed** | 2026-06-22 | Low |
| [[public-layout-dynamic-rendering-perf]] — public 레이아웃 동적 렌더링 TTFB 이슈 | **Closed** | 2026-06-22 | Medium |
| [[portfolio-add-switch-overflow]] — /portfolios/add Switch 터치 영역 scrollWidth false-positive | **Closed** | 2026-06-22 | P3 |
| [[topbar-global-search]] — TopBar 글로벌 검색 미구현 | **Closed (→ Spec)** | 2026-06-22 | P2 |
| [[portfolio-csv-upload]] — 포트폴리오 CSV N번 루프 → 벌크 엔드포인트 전환 필요 | **Closed (→ Spec: portfolio-csv-bulk-import)** | 2026-06-22 | P2 |
