---
type: moc
status: active
created: 2026-05-28
updated: 2026-06-09 (post code-review 8 spec 추가)
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
- [[sentiment-to-shared]] — Sentiment enum 이관: analysis 중첩 enum → shared/enums (cross-domain 해소)
- [[frontend-api-integration]] — FE 백엔드 API 실연동: 401 인터셉터·모든 훅 실데이터 연결
- [[frontend-oauth-social]] — 소셜 OAuth 실연동: 카카오·구글 콜백 Route Handler 구현
- [[payment-pg-integration]] — 결제 PG 연동: 카카오페이 정기결제·구독 관리·환불
- [[notification-read-status]] — 알림 읽음 처리: is_read 컬럼 + PATCH API + TopBar 미읽음 카운트
- [[frontend-share-card-image]] — 공유 카드 이미지: html2canvas PNG 다운로드·SNS 공유

**2026-06-09 코드 리뷰 후속 (수동 분석 + dc-review-code 종합 — 56개 이슈를 8개 Spec 으로 분리)**

- [[fe-auth-token-refresh-flow-rewrite]] — FE 토큰 갱신 흐름 재설계: Promise 큐 + httpOnly Set-Cookie + BroadcastChannel
- [[fe-correctness-investor-protection]] — FE 정확성·투자자 보호: sentiment 노출 가드·페이지네이션 정합·런타임 에러 차단
- [[architecture-refactoring-cleanup]] — 아키텍처/유지보수성 정리: DTO 패키지·Tier enum·cross-domain 의존·FE 중복 제거
- [[performance-caching-staletime]] — 성능/캐싱: Caffeine portfolios/analysis + TanStack staleTime + size 제한 + 복합 인덱스
- [[mvp-missing-endpoints]] — MVP 미구현 엔드포인트: phone verify · consents · pricing plans
- [[fe-accessibility-skeleton-ui]] — FE 접근성/Skeleton/UI 완성도: WCAG 2.1 AA + Skeleton 패턴 + TopBar 검색 결정

### Approved
- [[be-api-blocking-bugs-fix]] — BE API 블로킹 버그 일괄 픽스: JPQL/DTO/Tier 6건 P0
- [[security-hardening-mvp]] — 보안 강화 MVP: IDOR·CORS·CSP·Swagger·JWT 감사로그·Feedback 길이/TOCTOU

### Done
- [[disclosure-collection-pipeline]] — DART 공시 수집 파이프라인 Stage 1
- [[stocks-master-seed]] — 종목 마스터 시드/동기화 (코스피200+코스닥150)
- [[analysis-stage2-llm]] — LLM 분석 Stage 2
- [[user-auth-jwt-oauth2]] — M2 사용자인증 (JWT+AES256+OAuth2)
- [[notification-dispatcher]] — 알림 디스패처 MVP (Wave 1~3 + RetryJob 완료)
- [[notification-retry-job]] — 알림 재발송 배치 잡: ChannelSender 추출 + V15 + RetryJob (Wave 1+2 완료)
- [[sentiment-to-shared]] — Sentiment enum shared/enums 이관 (단일 Wave 완료)
- [[frontend-full-ui-implementation]] — 프론트엔드 전체 UI: 7 Zone·29카드·W1~W7 완료 (2026-06-09)

## 작업 흐름

```
/dc-plan <의도>        → Draft/<slug>.md 생성
/dc-tech-review <spec> → 작업 카드 분해, Draft→Approved 검토
/dc-spec-move <slug>   → 상태 전환 (frontmatter + git mv)
```
