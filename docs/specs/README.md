---
type: moc
status: active
created: 2026-05-28
updated: 2026-06-24 (dashboard-eval-pnl Done)
---

# Specs MOC

> 기능 명세(Spec) 문서 현황. 상태별 폴더로 관리.
> 상태 전환은 `/dc-spec-move <slug> <상태>` 사용.

## 폴더 구조

```
docs/specs/
├── Draft/      ← 작성 중 / 검토 전
├── Approved/   ← 기술 검토 완료, 구현 가능
└── Done/       ← 구현 + 테스트 완료
```

## 현황

### Draft
- [[payment-pg-integration]] — 결제 PG 연동: 카카오페이 정기결제·구독 관리·환불
- [[frontend-share-card-image]] — 공유 카드 이미지: html2canvas PNG 다운로드·SNS 공유

**2026-06-22 dc-review-frontend 리뷰 후속 (portfolios 페이지)**

- [[portfolio-csv-upload]] — 증권사 CSV 업로드 일괄 등록: 드래그앤드롭 + FE 파싱 + Free 한도 처리 (P3)

**2026-06-22 FE 최종 점검 후속 — FE↔BE API 전수 비교**

- [[be-api-alignment-mvp-r1]] — FE↔BE API 정합 R1: 알림 페이지네이션(P0) + rcept_dt 형식 수정(P1) + Stage 3/5 null 문서화 (2개 버그, DB 변경 없음)

**2026-06-24 dashboard-eval-pnl 코드리뷰 후속 이슈**

- [[krx-job-test-isolation]] — KRX 배치 잡 테스트 격리: KrxPriceSyncJob @ConditionalOnProperty + B128 HTTP→HTTPS 조사

### Approved

### Done
- [[dashboard-eval-pnl]] — 대시보드 평가 손익: KRX 종가 수집(MDCSTAT01501) + V23 마이그레이션 + 집계 API + FE 카드 교체 (2026-06-24 Done)
- [[krx-price-source-resilience]] — KRX 종가 소스 신뢰성: 이상치 필터(절대+전일비 ±50%) + HostWhitelist 주석 + 중장기 대체 소스 조사 (2026-06-24 Done)
- [[eval-pnl-integration-tests]] — eval-pnl 통합 테스트 보강: summary 6케이스 + KrxPriceSyncJobIntegrationTest 5케이스 + KrxClientTest isValidPrice 6경계값 (Option C, 166/166) (2026-06-24 Done)
- [[kakao-notification-channel]] — 알림채널 dev안정화: send() dev모드·isDevMode통일·.env.example SMTP/Kakao정합·설정저장토스트 (2026-06-24 Done)
- [[portfolio-review-followup]] — 포트폴리오 리뷰 후속: toResponse 오버로드·Stock 캐시·NFE 방어·max 검증·dead code 정리 (2026-06-24 Done)
- [[user-profile-investment-experience]] — 투자 경험·주 사용 시점 DB 저장: V22 마이그레이션 + nickname nullable + FE 호출 복원 (2026-06-23 Done)
- [[dashboard-real-data]] — 대시보드 실데이터 연동: R2 오늘 필터 + R3 Free 일 5건 BE 강제 + R4 FE 제한 안내 (2026-06-23 Done)
- [[oauth-consent-data-integrity]] — OAuth 동의 데이터 정합성: agreed_at nullable(V21) + 좀비 계정 배치 정리 (2026-06-23 Done)
- [[pricing-nav-auth-consistency]] — 요금제 네비/셸 정합: PublicNavbar auth-aware화 + (public)/layout 서버 쿠키 presence 판정 (2026-06-23 Done)
- [[portfolio-management-e2e]] — 포트폴리오 종목 관리 E2E: corp_name 응답·staleTime·알림 토글 Option A(설정 링크 교체) (2026-06-23 Done)
- [[fe-accessibility-skeleton-ui]] — FE 접근성/Skeleton/UI 완성도: WCAG 2.1 AA + Skeleton 패턴 + AlertDialog (2026-06-23 Done)
- [[performance-caching-staletime]] — 성능/캐싱: Caffeine+portfolioStockCodes/analysisResult + TanStack staleTime — 149/149 + 30/30 통과 (2026-06-23)
- [[e2e-token-refresh-mecount-fix]] — E2E meCallCount 오차 수정: concurrent-auth 픽스처 AuthBroadcastListener 조건부 마운트 (2026-06-23)
- [[portfolio-search-keyboard-nav]] — 종목 검색 드롭다운 키보드 네비: ArrowKey·Enter·activedescendant + useDebounce 공유훅 + Playwright 3케이스 (2026-06-23)
- [[oauth-consent-enforcement]] — OAuth 동의 강제화·UX: middleware E4 게이트 + JWT onboarding_completed + OAuthTermsPage + Vitest 30건 (2026-06-23)
- [[be-api-alignment-mvp-r1]] — FE↔BE API 정합 R1: 알림 PageResponse(P0) + rcept_dt YYYYMMDD(P1) + Stage null 문서화 (2026-06-23)
- [[code-review-fixes-onboarding-portfolio]] — 온보딩·포트폴리오 리뷰 수정: C-1·H 7건·M 5건·L 4건 완료 (2026-06-18)
- [[mvp-missing-endpoints]] — MVP 미구현 엔드포인트: phone verify · consents · pricing plans (2026-06-11)
- [[frontend-oauth-social]] — 소셜 OAuth 실연동: 카카오·구글 콜백 Route Handler 구현 (2026-06-11)
- [[notification-read-status]] — 알림 읽음 처리: is_read 컬럼 + PATCH API + TopBar 미읽음 카운트 (2026-06-11)
- [[architecture-refactoring-cleanup]] — 아키텍처·유지보수성 정리: DTO 패키지·Tier enum·cross-domain·FE 중복 (2026-06-10)
- [[be-api-blocking-bugs-fix]] — BE API 블로킹 버그 일괄 픽스: JPQL/DTO/Tier 6건 P0 + 테스트 23건 (2026-06-10)
- [[security-hardening-mvp]] — 보안 강화 MVP: IDOR·CORS·CSP·Swagger·JWT·Feedback DoS — R1~R14 + 테스트 8건 (2026-06-10)
- [[disclosure-collection-pipeline]] — DART 공시 수집 파이프라인 Stage 1
- [[stocks-master-seed]] — 종목 마스터 시드/동기화 (코스피200+코스닥150)
- [[analysis-stage2-llm]] — LLM 분석 Stage 2
- [[user-auth-jwt-oauth2]] — M2 사용자인증 (JWT+AES256+OAuth2)
- [[notification-dispatcher]] — 알림 디스패처 MVP (Wave 1~3 + RetryJob 완료)
- [[notification-retry-job]] — 알림 재발송 배치 잡: ChannelSender 추출 + V15 + RetryJob (Wave 1+2 완료)
- [[sentiment-to-shared]] — Sentiment enum shared/enums 이관 (단일 Wave 완료)
- [[frontend-full-ui-implementation]] — 프론트엔드 전체 UI: 7 Zone·29카드·W1~W7 완료 (2026-06-09)
- [[fe-auth-token-refresh-flow-rewrite]] — FE 토큰 갱신 흐름 재설계: Promise 큐 + httpOnly + BroadcastChannel + Playwright E2E (2026-06-10)
- [[fe-correctness-investor-protection]] — FE 정확성·투자자 보호: R1 sentiment 가드·R3 BE JOIN·R4~R7 런타임에러 (2026-06-10)
- [[landing-hero-mockup-enhancement]] — 랜딩 히어로 목업 강화: placeholder→시뮬레이션 카드+진입 애니메이션+SSR 리다이렉트 (2026-06-18)
- [[frontend-api-integration]] — FE BE API 실연동: env prefix·fetchMe 부트스트랩·Sonner Toast (2026-06-10)

## 작업 흐름

```
/dc-plan <의도>        → Draft/<slug>.md 생성
/dc-tech-review <spec> → 작업 카드 분해, Draft→Approved 검토
/dc-spec-move <slug>   → 상태 전환 (frontmatter + git mv)
```
