---
type: moc
status: active
created: 2026-05-28
updated: 2026-06-11 (mvp-missing-endpoints Done 전환)
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
- [[fe-accessibility-skeleton-ui]] — FE 접근성/Skeleton/UI 완성도: WCAG 2.1 AA + Skeleton 패턴 + TopBar 검색 결정

**2026-06-16 OAuth 소셜 가입 플로우 수정 후속 (코드리뷰 Medium/High 이슈 Spec화)**

- [[oauth-consent-enforcement]] — OAuth 동의 강제화·UX: middleware consent 체크 + JWT claims 인코딩 + OAuthTermsPage 분리
- [[oauth-consent-data-integrity]] — OAuth 동의 데이터 정합성: agreed_at nullable 마이그레이션(V19) + 캐싱 + 미완료 계정 배치 정리

### Approved
- [[code-review-fixes-onboarding-portfolio]] — 온보딩·포트폴리오 리뷰 수정: Critical(V10 충돌) + High 7건 + Medium 5건 + Low 4건 (2026-06-17 승인)

### Done
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
- [[frontend-api-integration]] — FE BE API 실연동: env prefix·fetchMe 부트스트랩·Sonner Toast (2026-06-10)

## 작업 흐름

```
/dc-plan <의도>        → Draft/<slug>.md 생성
/dc-tech-review <spec> → 작업 카드 분해, Draft→Approved 검토
/dc-spec-move <slug>   → 상태 전환 (frontmatter + git mv)
```
