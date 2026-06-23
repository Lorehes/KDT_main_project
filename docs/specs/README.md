---
type: moc
status: active
created: 2026-05-28
updated: 2026-06-23 (e2e-token-refresh-mecount-fix Draft 추가)
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

**2026-06-09 코드 리뷰 후속 (수동 분석 + dc-review-code 종합 — 56개 이슈를 8개 Spec 으로 분리)**

- [[performance-caching-staletime]] — 성능/캐싱: Caffeine portfolios/analysis + TanStack staleTime + size 제한 + 복합 인덱스

**2026-06-22 dc-review-frontend 리뷰 후속 (portfolios 페이지)**

- [[portfolio-search-keyboard-nav]] — 종목 검색 드롭다운 ARIA 키보드 네비게이션: ArrowDown/ArrowUp/Enter + aria-activedescendant (P1)
- [[portfolio-csv-upload]] — 증권사 CSV 업로드 일괄 등록: 드래그앤드롭 + FE 파싱 + Free 한도 처리 (P3)

**2026-06-22 FE 최종 점검 후속 — FE↔BE API 전수 비교**

- [[be-api-alignment-mvp-r1]] — FE↔BE API 정합 R1: 알림 페이지네이션(P0) + rcept_dt 형식 수정(P1) + Stage 3/5 null 문서화 (2개 버그, DB 변경 없음)

**2026-06-21 portfolio-management-e2e 리뷰 후속**

- [[portfolio-review-followup]] — 포트폴리오 리뷰 후속: toResponse 오버로드·Stock 캐시·NFE 방어·max 검증·Bell 링크화 (M-1·M-4·M-5·M-6·L-1·L-2·L-3·L-5)

**2026-06-16 OAuth 소셜 가입 플로우 수정 후속 (코드리뷰 Medium/High 이슈 Spec화)**

- [[oauth-consent-data-integrity]] — OAuth 동의 데이터 정합성: agreed_at nullable 마이그레이션(V21) + 캐싱 + 미완료 계정 배치 정리
- [[e2e-token-refresh-mecount-fix]] — E2E 토큰 갱신 테스트 픽스: AuthBroadcastListener 조건부 마운트로 meCallCount 오차 수정 (1줄 수정)

### Approved
- [[oauth-consent-enforcement]] — OAuth 동의 강제화·UX: middleware consent 체크 + JWT onboarding_completed 인코딩 + OAuthTermsPage 분리 (2026-06-23 Approved)
- [[fe-accessibility-skeleton-ui]] — FE 접근성/Skeleton/UI 완성도: WCAG 2.1 AA + Skeleton 패턴 + AlertDialog (2026-06-22 Approved)

### Done
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
