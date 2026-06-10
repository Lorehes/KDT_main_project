---
type: team
status: active
created: 2026-06-02
updated: 2026-06-02
---

# 개발 점수 기록

> dc-push 스킬을 통해서만 점수 추가. 수동 수정 시 `git blame`에 이메일로 표시.

## 점수 기록

| 날짜 | 멤버 | SHA | 커밋메시지 | 레이어 | 중요 | 품질 | 규모 | 요약 |
|------|------|-----|-----------|--------|------|------|------|------|
| 2026-06-02 | Jin | 6d371d4 | feat(stocks): stocks 도메인 도입 + disclosure N+1 해소 [7/8 L] | Stocks/Disclosure/Infra | 7 | 8 | L | stocks 도메인 도입+N+1 해소 |
| 2026-06-02 | Jin | c106214 | feat(disclosure): 백필 + spring-retry + KrxClient 실구현 [8/8 L] | Disclosure/Infra/Stocks/Scripts | 8 | 8 | L | 3년치 백필 + Thread.sleep 제거 |
| 2026-06-02 | Jin | 1fcf4cc | refactor(security): deferred 전건 해결 + Spring Security 도입 [7/8 L] | Disclosure/Infra/Shared | 7 | 8 | L | deferred 7건 해결 + /admin Basic 가드 |
| 2026-06-02 | Jin | 536ad16 | feat(disclosure): 비동기 백필 잡 + 진행률 조회 [7/8 M] | Disclosure | 7 | 8 | M | @Async + jobId + chunksDone/Total |
| 2026-06-02 | Jin | 2773216 | feat(stocks): seed 341 stocks (KOSPI200+KOSDAQ150 via FDR fallback) [7/7 M] | Stocks/Scripts | 7 | 7 | M | V10 시드 적재 |
| 2026-06-03 | Jin | d4153d2 | refactor(classifier): OTHER 룰 16종 보강 + 운영 환경 정합 [6/8 M] | Disclosure/Infra | 6 | 8 | M | OTHER 61%→8% + docker/env 부팅 정합 |
| 2026-06-07 | Jin | 7b0aa02 | feat(user): M2 JWT+AES256 사용자인증 인프라 Wave 1 [9/8 L] | User/Shared | 9 | 8 | L | M2 JWT+AES256 사용자인증 인프라 |
| 2026-06-07 | Jin | 3c9bc3c | feat(user): M2 사용자·포트폴리오·알림설정 Wave 3 [9/8 L] | User/Shared/Stocks | 9 | 8 | L | IDOR+AES+Free제한+알림설정 구현 |
| 2026-06-07 | Jin | c0fca6f | feat(user): M2 이메일 Auth 서비스+컨트롤러+DTO Wave 2 [9/8 M] | User/Shared | 9 | 8 | M | signup/login/refresh/logout 구현 |
| 2026-06-07 | Jin | 1ffec24 | feat(user): M2 OAuth 2.0 Kakao/Google/Naver Wave 4 [8/8 L] | User/Infra | 8 | 8 | L | OAuth 3사 로그인 + state CSRF |
| 2026-06-08 | Jin | d09deac | test(user): M2 Wave 5 통합테스트+기존 31건 수정 [8/8 L] | User/Analysis/Disclosure | 8 | 8 | L | JWT/IDOR/AES/Free제한 E2E 검증 |
| 2026-06-08 | Jin | 1b18cd0 | feat(notification): M3 Wave 1 알림 인프라 기반 [8/8 L] | Notification/Infra/Shared | 8 | 8 | L | M3 알림 인프라 기반 구축 |
| 2026-06-08 | Jin | cab1b2e | feat(notification): M3 Wave 2 디스패처 코어 [9/8 M] | Notification | 9 | 8 | M | INSTANT 4단계 필터+채널라우팅 |
| 2026-06-08 | Jin | fd0d848 | test(notification): M3 Wave 3 디스패처 통합 테스트 [8/9 S] | Notification | 8 | 9 | S | 11 통합 테스트+Awaitility 수정 |
| 2026-06-08 | Jin | 59c7cef | feat(notification): M3 RetryJob+ChannelSender분리+Wave2테스트 [8/9 M] | Notification | 8 | 9 | M | 알림재시도 RetryJob+ChannelSender분리 |
| 2026-06-08 | Jin | d54f6ab | refactor(shared): Sentiment enum shared/enums 이관 [5/8 L] | Shared/Analysis/Notification/Infra | 5 | 8 | L | Sentiment enum shared/enums 이관 |
| 2026-06-09 | Jin | 391d07d | feat(frontend): FE W1 레이아웃+컴포넌트 기반 구축 [5/8 L] | Frontend | 5 | 8 | L | FE W1 레이아웃·컴포넌트 기반 구축 |
| 2026-06-09 | Jin | d055be0 | feat(frontend): FE W2 랜딩·요금제 페이지 [4/8 S] | Frontend | 4 | 8 | S | 랜딩 히어로+기능그리드·요금제 플랜카드 |
| 2026-06-09 | Jin | 2243acf | feat(frontend): FE W3 온보딩 플로우 전체 [6/8 M] | Frontend | 6 | 8 | M | 로그인·가입·OTP·약관·휴대폰·프로필·완료 |
| 2026-06-09 | Jin | 49c63ac | feat(frontend): FE W4 공시 피드+상세 [7/8 M] | Frontend | 7 | 8 | M | 공시피드 필터·공시상세 Free·Pro·Premium |
| 2026-06-09 | Jin | f6ff427 | feat(frontend): FE W5 종목 관리 [6/8 S] | Frontend | 6 | 8 | S | 종목목록+쿼터바·등록상세+매수가·수량입력 |
| 2026-06-09 | Jin | ad7031f | feat(frontend): FE W6 알림 설정·센터·모달 [6/8 M] | Frontend | 6 | 8 | M | 알림설정·알림톡미리보기·벨팝오버·알림센터 |
| 2026-06-09 | Jin | 0baafaa | feat(frontend): FE W7 결제·계정·공유 [5/8 S] | Frontend | 5 | 8 | S | Checkout mockup·마이페이지·공유카드·전체완료 |
| 2026-06-10 | Jin | ecfc2d9 | fix(disclosure): BE P0 블로킹 버그 6건 픽스+테스트21건 [8/9 L] | Disclosure/Analysis/User | 8 | 9 | L | BE P0 버그 6건 픽스+테스트21건 |
| 2026-06-10 | Jin | 32ef4f7 | fix(security): 보안강화MVP P0~P1 14건+FE-BE정합미커밋 [9/9 L] | Shared/Analysis/Disclosure/Frontend | 9 | 9 | L | 보안강화MVP P0~P1 14건+FE-BE정합미커밋 |
| 2026-06-10 | Jin | 6ea3332 | feat(frontend): FE 토큰갱신 Promise큐+BroadcastChannel 다중탭동기화 [8/9 L] | Frontend | 8 | 9 | L | FE 토큰갱신 Promise큐+다중탭동기화 |
| 2026-06-10 | Jin | 3aca87e | test(frontend): R10 Playwright E2E 인증플로우 4건 [6/9 M] | Frontend | 6 | 9 | M | R10 E2E 인증플로우 4건 |
| 2026-06-10 | Jin | d83880c | fix(frontend): 투자자보호 sentiment가드+페이지네이션정합+런타임에러 [7/9 M] | Frontend/Disclosure | 7 | 9 | M | 투자자보호·런타임에러·페이지정합 |
| 2026-06-10 | Jin | 7796f54 | refactor(arch): 아키텍처정리+도메인경계복구+테스트9건 [5/9 L] | Shared/Disclosure/Analysis/User/Frontend | 5 | 9 | L | 아키텍처정리+도메인경계복구+테스트9건 |
| 2026-06-10 | Jin | 0e163c8 | test(security): 보안테스트8건+Spec2개Done [6/8 S] | Shared/Docs | 6 | 8 | S | 보안테스트8건+Spec2개Done |
| 2026-06-10 | Jin | 80d182e | chore(spec): 아키텍처정리Spec Done전환 [2/8 S] | Docs | 2 | 8 | S | 아키텍처정리Spec Done전환 |
<!-- SCORES -->

## 기간별 집계

<!-- SUMMARY:START -->
| 멤버 | 오늘 커밋 | 7일 커밋 | 30일 커밋 | 평균 품질 | 평균 중요도 |
|------|-----------|----------|-----------|----------|------------|
| Jin | 8 | 24 | 31 | 8.2 | 6.9 |
<!-- SUMMARY:END -->
