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
| 2026-06-10 | Jin | 40d045f | feat(frontend): API연동정합+인증부트스트랩+SonnerToast [6/7 M] | Frontend | 6 | 7 | M | API연동정합+인증부트스트랩+Toast |
| 2026-06-10 | Jin | a7b1cc1 | feat(user): MVP 가입OTP·동의·요금제API 완성 [7/8 L] | User/Infra/Frontend | 7 | 8 | L | MVP 가입OTP·동의·요금제API 완성 |
| 2026-06-10 | Jin | 24c663e | test(user): Phone·동의·요금제 Wave4 통합테스트 19건 [6/8 M] | User | 6 | 8 | M | Phone·동의·요금제 통합테스트 |
| 2026-06-11 | Jin | 521badf | feat(user): 이메일OTP 가입검증 완성 [8/9 L] | User/Frontend | 8 | 9 | L | 이메일OTP 가입검증 완성 |
| 2026-06-11 | Jin | a1d773e | feat(notification,frontend): OAuth소셜실연동+알림읽음영속화 [8/9 L] | Notification/User/Frontend | 8 | 9 | L | OAuth소셜로그인+알림읽음영속화 |
| 2026-06-11 | Jin | 4947620 | chore(spec): mvp-missing-endpoints Done 전환 [2/8 S] | Docs | 2 | 8 | S | MVP미구현엔드포인트 Done전환 |
| 2026-06-11 | Jin | 31cbdad | fix(frontend): 명세서 기반 디자인 정합성 수정 [6/8 M] | Frontend/Docs | 6 | 8 | M | CSP버그수정+Primary색상+명세UI정합 |
| 2026-06-16 | Jin | d6b55f3 | fix(shared,user): JWT 쿠키 지원+OAuth 이메일 placeholder+Email rate복원 [8/7 S] | Shared/User | 8 | 7 | S | JWT 쿠키 지원+OAuth placeholder처리 |
| 2026-06-16 | Jin | 1a29007 | fix(frontend): 온보딩 UI 개선 — 약관 필수동의+휴대폰 레이아웃 [5/7 S] | Frontend | 5 | 7 | S | 약관필수동의+휴대폰 입력 UI 개선 |
| 2026-06-16 | Jin | cdbca15 | fix(frontend): auth 에러처리+공개경로 리다이렉트+레이아웃 반응형 [7/7 S] | Frontend | 7 | 7 | S | auth에러체크+공개경로guard+OTP반응형 |
| 2026-06-16 | Jin | e408d34 | chore(docs): AGE설계결정+마일스톤+gitignore+Vault MOC [3/8 S] | Docs | 3 | 8 | S | AGE 미저장 설계결정+MVP마일스톤 |
| 2026-06-16 | Jin | ffbe092 | fix(infra,user): Kakao dev모드+rate limit 롤백+개발 rate스킵 [7/8 S] | Infra/User | 7 | 8 | S | Kakao placeholder모드+rate롤백 |
| 2026-06-16 | Jin | 0e2a632 | fix(frontend): 휴대폰UX+OTP만료+프로필토글+OAuth URL버그 [7/7 M] | Frontend | 7 | 7 | M | 번호포맷+OTP만료UX+OAuth URL수정 |
| 2026-06-16 | Jin | 9202dd9 | fix(user,frontend): OAuth 가입 약관동의 버그 수정+최적화 [8/8 L] | User/Frontend | 8 | 8 | L | OAuth 가입 약관동의 버그 수정+최적화 |
| 2026-06-16 | Jin | b3dfd34 | chore(frontend): signup 콜백 주석 업데이트+devlog 정정 [3/8 S] | Frontend | 3 | 8 | S | signup 주석 업데이트+devlog 정정 |
| 2026-06-16 | Jin | 6118a6f | fix(frontend): 모바일 auth 헤딩 반응형+로그인 문구 개선 [4/8 S] | Frontend | 4 | 8 | S | 모바일 auth 헤딩 반응형+문구개선 |
| 2026-06-16 | Jin | 4cae162 | docs(specs): 스펙 감사+신규3개+테크리뷰 [2/8 M] | Docs | 2 | 8 | M | 스펙 감사+신규3개+테크리뷰 |
| 2026-06-16 | Jin | 97a6900 | feat(user,frontend): 포트폴리오 종목명+staleTime+알림토글제거 [6/8 M] | User/Frontend | 6 | 8 | M | 포트폴리오 종목명+staleTime+알림토글제거 |
| 2026-06-16 | Jin | c717efe | fix(frontend): 종목등록 필수입력+검색UX개선 [5/8 S] | Frontend | 5 | 8 | S | 종목등록 필수입력+검색UX개선 |
| 2026-06-16 | Jin | 476f436 | fix(frontend): 종목등록 진입경로 수정+code없음 redirect [6/8 S] | Frontend | 6 | 8 | S | 종목등록 진입경로+redirect 수정 |
| 2026-06-17 | Jin | e438705 | feat(frontend): 온보딩 체크리스트 Sheet/Dialog 전환 [6/8 S] | Frontend | 6 | 8 | S | 온보딩 체크리스트 인라인 완료 |
| 2026-06-17 | Jin | f579be2 | fix(frontend): 온보딩 a11y 버튼라벨+middleware 보호 [5/8 S] | Frontend | 5 | 8 | S | 버튼aria-label+/complete보호 |
| 2026-06-17 | Jin | ad2bbcc | fix(shared,frontend): 온보딩 409 버그 수정 — BE 에러계약+FE 상태파생 [7/8 S] | Shared/Frontend | 7 | 8 | S | 온보딩 409 에러 근본 수정 |
| 2026-06-17 | Jin | d81e826 | feat(user,frontend): OAuth 온보딩 전 절차 완료 강제 — onboarding_completed_at [7/8 M] | User/Frontend | 7 | 8 | M | OAuth 온보딩 전 절차 완료 강제 |
| 2026-06-17 | Jin | d7128ea | fix(user,frontend,shared): 코드 리뷰 전수 수정 — V20 마이그레이션·OAuth 세션·보안·UX [7/8 L] | User/Shared/Frontend | 7 | 8 | L | 코드리뷰 Critical~Low 전수 수정 |
| 2026-06-17 | Jin | 6fa9848 | fix(frontend,docs): dc-review-frontend Low 수정 + 랜딩 히어로 목업 스펙 [3/8 S] | Frontend/Docs | 3 | 8 | S | 온보딩 아이콘·공백 수정+랜딩 목업 스펙 |
| 2026-06-18 | Jin | 4a30ad8 | feat(frontend): LP 히어로 목업+SSR 리다이렉트 전환 [4/8 M] | Frontend | 4 | 8 | M | LP 히어로 목업+SSR 리다이렉트 전환 |
| 2026-06-18 | Jin | decaf15 | chore(docs): 랜딩 목업 스펙 Done 전환 [2/8 S] | Docs | 2 | 8 | S | 랜딩 목업 스펙 Done 전환 |
| 2026-06-18 | Jin | e5a5193 | chore(docs): code-review-fixes-onboarding-portfolio Spec Done 전환 [2/8 S] | Docs | 2 | 8 | S | 온보딩·포트폴리오 리뷰 Spec Done 전환 |
| 2026-06-21 | Jin | 081de60 | fix(test,frontend,docs): 포트폴리오 리뷰 H-1·M-2·M-3 수정 + 후속 Spec [5/8 M] | Test/Frontend/Docs | 5 | 8 | M | corp_name 테스트+Number변환+deadcode+후속Spec |
| 2026-06-22 | Jin | 80c3abd | fix(frontend): PublicNavbar auth-aware CTA + nav 정리 [6/8 M] | Frontend | 6 | 8 | M | PublicNavbar 인증분기+네비정리 |
| 2026-06-22 | Jin | 26a738a | feat(frontend): WCAG AA 접근성+Skeleton UI+AlertDialog M2 완료 [7/8 L] | Frontend | 7 | 8 | L | WCAG AA 접근성+Skeleton UI M2 완료 |
| 2026-06-22 | Jin | 223d39e | feat(frontend): TopBar팝오버+설정레이아웃+모바일메뉴+버그수정9건 [7/7 L] | Frontend | 7 | 7 | L | TopBar팝오버+설정레이아웃+버그9건수정 |
| 2026-06-22 | Jin | a13613e | feat(frontend): 포트폴리오 검색 UX 재설계+접근성 수정 [5/7 M] | Frontend/Docs | 5 | 7 | M | 포트폴리오 검색 UX 재설계+접근성 수정 |
| 2026-06-22 | Jin | 699d1de | feat(frontend): 포트폴리오 라우트 재설계+대시보드+등록폼 완성 [6/8 M] | Frontend/Docs | 6 | 8 | M | 포트폴리오 라우트 재설계+등록폼 완성 |
| 2026-06-22 | Jin | 4b29f38 | feat(frontend): 공시피드 2컬럼 레이아웃 재배치 [3/7 S] | Frontend | 3 | 7 | S | 공시피드 2컬럼 레이아웃 재배치 |
| 2026-06-22 | Jin | 73d5488 | docs(spec): FE↔BE API 정합 Spec 수립 [5/8 S] | Docs | 5 | 8 | S | FE↔BE API 정합 Spec 수립 |
| 2026-06-23 | Jin | f3ce93f | fix(notification,disclosure): FE-BE API 정합 P0·P1 수정 [7/8 M] | Notification/Disclosure/Frontend | 7 | 8 | M | 알림페이지네이션+rcept_dt포맷정합 |
| 2026-06-23 | Jin | bf83745 | fix(user,frontend): E4 OAuth 약관게이트+JWT claim+Vitest 30테스트 [8/8 L] | User/Shared/Frontend | 8 | 8 | L | E4 OAuth 약관게이트+JWT claim+Vitest |
| 2026-06-23 | Jin | 81eaeae | feat(frontend): 종목검색 키보드네비+WAI-ARIA+Playwright E2E [6/8 M] | Frontend | 6 | 8 | M | 종목검색 키보드네비+WAI-ARIA+Playwright |
| 2026-06-23 | Jin | 4dc407c | fix(frontend): E2E meCallCount 픽스 — AuthBroadcastListener 조건부 마운트 [4/8 S] | Frontend/Docs | 4 | 8 | S | E2E meCallCount 픽스 |
| 2026-06-23 | Jin | 15fac11 | feat(perf): Caffeine 캐시 인프라 + staleTime 정책 [7/8 L] | Shared/Analysis/User/Frontend | 7 | 8 | L | Caffeine 캐시 인프라 + staleTime 정책 |
| 2026-06-23 | Jin | 2a5f497 | chore(spec): fe-accessibility-skeleton-ui Approved→Done [2/8 S] | Docs | 2 | 8 | S | fe-accessibility-skeleton-ui Spec Done 전환 |
| 2026-06-23 | Jin | 0fd5092 | feat(frontend): 포트폴리오 알림 토글 dead code 제거 [3/8 S] | Frontend | 3 | 8 | S | 알림토글 제거+설정 링크 안내 |
| 2026-06-23 | Jin | 928dcf8 | fix(user): OAuth agreed_at 정합+좀비계정 배치 [7/7 M] | User | 7 | 7 | M | OAuth agreed_at 정합+좀비계정 배치 |
| 2026-06-23 | Jin | 7061755 | feat(disclosure,frontend): 대시보드 오늘 필터+Free 5건/일 강제 [7/8 M] | Disclosure/Frontend | 7 | 8 | M | 대시보드 오늘필터+Free 5건 강제 |
| 2026-06-23 | Jin | 01b9f3f | feat(user,frontend): 투자경험·주사용시점 DB 저장 활성화 (V22) [7/8 L] | User/Frontend | 7 | 8 | L | 투자경험·사용시점 DB 저장 활성화 |
| 2026-06-23 | Jin | 6227306 | refactor(stocks,user,frontend): 포트폴리오 캐시+NFE방어+FE검증+dead code 정리 [5/8 M] | Stocks/User/Frontend | 5 | 8 | M | 포트폴리오 캐시·NFE방어·FE검증 정리 |
| 2026-06-24 | Jin | 2f9c166 | feat(notification,infra): 알림채널 dev안정화+이메일폴백+설정저장토스트 [5/8 M] | Notification/Infra/Frontend | 5 | 8 | M | 알림채널 dev안정화+이메일폴백 정합 |
| 2026-06-24 | Jin | abc7c2e | feat(stocks,user,frontend): 평가손익PnlCard+KRX종가동기화+버그3건픽스 [8/8 L] | Stocks/User/Infra/Frontend | 8 | 8 | L | 평가손익PnlCard+KRX종가+버그3건픽스 |
| 2026-06-24 | Jin | 06facca | fix(infra,stocks): KRX 이상치 2단 방어+리뷰 Low 수정+이슈 문서화 [6/8 M] | Infra/Stocks/Docs | 6 | 8 | M | KRX 이상치 2단 방어+리뷰 Low 수정 |
| 2026-06-24 | Jin | f2f3a07 | test(user,stocks,infra): eval-pnl 통합 테스트 17케이스+isValidPrice package-private [7/8 M] | User/Stocks/Infra | 7 | 8 | M | eval-pnl/KRX 통합 테스트 17케이스 |
| 2026-06-24 | Jin | b545bfc | chore(stocks,infra): KRX 잡 테스트 격리+B128 HTTPS [4/9 M] | Stocks/Infra | 4 | 9 | M | KRX 잡 테스트 격리+B128 HTTPS |
| 2026-06-24 | Jin | c7b8064 | feat(frontend): CSV 업로드 일괄등록+EUC-KR 바이너리 테스트 [7/9 M] | Frontend | 7 | 9 | M | CSV업로드 드래그앤드롭+EUC-KR 바이너리 |
| 2026-06-24 | Jin | 64848e7 | feat(frontend): 공유 카드 이미지 캡처+Playwright E2E 6건 [6/8 M] | Frontend | 6 | 8 | M | html2canvas-pro+Playwright E2E 6건 |
| 2026-06-24 | Jin | 432aa37 | feat(skill): dc-review-frontend --auth 인증 모드 확장 [3/8 M] | Docs | 3 | 8 | M | --auth sentinel·login·state 4모드 |
| 2026-06-24 | Jin | 5ee0632 | chore(spec): hover-capture Spec Approved+dev-log [2/8 S] | Docs | 2 | 8 | S | hover캡처 Spec Approved 전환 |
| 2026-06-25 | Jin | cc8f11d | feat(frontend): 알림 센터 더 보기 페이지네이션 [6/8 M] | Frontend | 6 | 8 | M | 알림센터 더 보기 페이지네이션 구현 |
| 2026-06-25 | Jin | 08af22b | feat(infra): OpenRouter LLM 프로덕션 전환+리뷰 8항목 수정 [8/8 M] | Infra/Shared | 8 | 8 | M | OpenRouter 전환+H1/M1/M2/M3/L1~L4 수정 |
| 2026-06-25 | Jin | b28e4db | feat(infra): M4 배포인프라 Docker+CI/CD+리뷰9항목수정 [8/8 L] | Infra/Frontend/Docs | 8 | 8 | L | M4 배포인프라 Docker+CI/CD 완성 |
| 2026-06-25 | Jin | 4cd8b19 | fix(user,frontend): 이슈12건·BE버그픽스·FE기술부채·테스트보강 [6/8 L] | User/Frontend/Test/Docs | 6 | 8 | L | 이슈12건·BE버그픽스·FE기술부채 |
| 2026-06-25 | Jin | b1494b5 | feat(disclosure,frontend): TopBar 글로벌 검색 + 리뷰 8건 수정 [6/8 L] | Disclosure/Frontend | 6 | 8 | L | TopBar 글로벌 검색+리뷰8건수정 |
| 2026-06-26 | Jin | f6510f2 | feat(user,frontend): CSV 종목 일괄등록 API+FE단일호출 [6/8 M] | User/Frontend | 6 | 8 | M | CSV 종목 일괄등록 API+FE단일호출 |
| 2026-06-29 | Jin | 1af21fd | feat(user): 포트폴리오 요약 응답 DTO [5/9 S] | User | 5 | 9 | S | 포트폴리오 요약 응답 DTO |
| 2026-06-29 | Jin | 9e22ab0 | chore(infra): PostgreSQL15 스키마 권한 init 스크립트 [6/8 S] | Infra | 6 | 8 | S | PostgreSQL15 스키마 권한 init 스크립트 |
| 2026-06-29 | Jin | 320a6c2 | feat(disclosure): disclosure-content-text-fetch 구현 + 리뷰 이슈 전량 수정 [8/9 L] | Disclosure/Infra/Shared | 8 | 9 | L | 공시 본문 fetch 파이프라인 + 보안 HIGH 6건 |
| 2026-06-29 | Jin | 1463ed3 | feat(disclosure): 백필 커서 페이지네이션 + 리뷰 수정 [7/9 M] | Disclosure/Infra | 7 | 9 | M | 백필 커서 페이지네이션+단위테스트 |
| 2026-06-29 | Jin | ef03d2d | feat(disclosure): 백필 잡 추적·탄력성 강화 + 리뷰 7건 수정 [7/9 L] | Disclosure/Infra/Shared | 7 | 9 | L | 백필 잡 추적+탄력성 강화 |
| 2026-06-30 | Jin | 8e5f19c | feat(analysis,infra): Stage 3 RAG Chroma+임베딩 구현 + 리뷰 5건 수정 [9/8 L] | Analysis/Infra | 9 | 8 | L | Stage3 RAG Chroma+임베딩 구현 |
| 2026-06-30 | Jin | 3b3b0df | fix(disclosure,analysis,frontend): 공시 q검색 lower(bytea) 버그+경고 정리 [7/8 M] | Disclosure/Analysis/Frontend | 7 | 8 | M | 공시 q검색 lower(bytea) 버그+경고 정리 |
| 2026-07-01 | Jin | 303792e | chore(ops): DB_URL 오설정 수정 + 93k 콘텐츠 백필 완료 [5/7 S] | Ops | 5 | 7 | S | DB_URL 5432→5433 수정+93k 백필 완료 |
| 2026-07-01 | Jin | 0a07004 | feat(analysis,infra): Stage 3 임베딩 백필+절삭 버그 수정 [8/9 L] | Analysis/Infra | 8 | 9 | L | Stage3 임베딩 백필 + 6700자↑ 500 버그 해소 |
| 2026-07-01 | Jin | 69eb715 | docs(infra): LLM_BASE_URL 미설정 함정 .env.example 문서화 [7/8 S] | Docs/Infra | 7 | 8 | S | LLM 404 재발 방지 — OLLAMA_BASE_URL 혼동 주석 |
| 2026-07-02 | Jin | 084e5c7 | feat(frontend): 공시 상세 Wave1 시각 리디자인 + 피드 정렬 버그 수정 [4/8 M] | Frontend | 4 | 8 | M | 공시 상세 Wave1 시각 리디자인 |
| 2026-07-02 | Jin | a1959f5 | feat(analysis,frontend): Stage2 요인/해설 생성+노출 Wave2 [7/9 L] | Analysis/Infra/Frontend | 7 | 9 | L | Stage2 요인/해설 생성+노출 |
| 2026-07-02 | Jin | 6768606 | feat(user,frontend): 매수가 박스 + 유사공시 크래시 수정 Wave3 [6/8 L] | User/Frontend | 6 | 8 | L | 매수가 박스 + 유사공시 크래시 수정 |
| 2026-07-02 | Jin | 97ab0ce | feat(stocks): KRX 주가 시계열 Wave A — V27+일배치 병행 [6/9 M] | Stocks/Infra | 6 | 9 | M | stock_prices 시계열 테이블+일배치 |
| 2026-07-02 | Jin | 392b266 | feat(stocks): KRX 과거 3년 주가 백필 Wave B [6/9 L] | Stocks/Infra | 6 | 9 | L | 과거 주가 백필 잡+안전망 |
| 2026-07-02 | Jin | 82e5543 | feat(analysis,stocks,frontend): 예측 차트 반응 산출 Wave C [7/9 L] | Analysis/Stocks/Frontend | 7 | 9 | L | 유사공시 D+1~D+5 평균 예측 차트 |
| 2026-07-03 | Jin | 09942a0 | feat(analysis,infra): Stage2 프롬프트 본문 투입 + num_ctx 상향 [8/9 M] | Analysis/Infra | 8 | 9 | M | Stage2 본문 발췌 투입 |
| 2026-07-03 | Jin | 782745e | fix(analysis): PromptGuard 법률용어 오탐 개선 [6/9 S] | Analysis | 6 | 9 | S | 매수/매도 오탐 → 권유맥락 패턴 |
| 2026-07-03 | Jin | e1eae46 | fix(infra): 공시 본문 charset 프로빙 — mojibake 수정 [7/9 S] | Infra | 7 | 9 | S | UTF-8 우선 strict 프로빙 |
| 2026-07-04 | Jin | 37a1bb1 | chore(infra): .env 단일화 + Lightsail 배포 스택 정리 [6/8 M] | Infra | 6 | 8 | M | .env 단일 파일 서버 배포화 |
| 2026-07-04 | Jin | f5ce48f | fix(frontend): ESLint 에러 5곳 수정 — 빌드 게이트 복구 [7/8 S] | Frontend | 7 | 8 | S | ESLint 에러 5곳 수정 (빌드 차단) |
| 2026-07-04 | Jin | f669dfb | fix(frontend): SSG prerender 에러 수정 — /test/concurrent-auth force-dynamic [7/8 S] | Frontend | 7 | 8 | S | prerender 에러 수정 (force-dynamic) |
| 2026-07-04 | Jin | c89e8c5 | fix(frontend): prerender 에러 Suspense 근본 수정 — concurrent-auth [7/9 S] | Frontend | 7 | 9 | S | prerender 에러 Suspense로 근본 수정 |
| 2026-07-05 | Jin | 93dd520 | feat(analysis): Stage2 타입 게이트 Tier1 — LLM 볼륨 ~36% 절감 [8/9 S] | Analysis (LLM 분석) | 8 | 9 | S | Stage2 타입 게이트 Tier1 구현 |
| 2026-07-05 | Jin | b71b844 | fix(infra): mail 헬스 지표 비활성 — backend unhealthy 오탐 수정 [7/8 S] | Backend | 7 | 8 | S | mail 헬스 지표 비활성 (unhealthy 오탐) |
| 2026-07-05 | Jin | 0b26c03 | fix(frontend): CSP script-src unsafe-inline 추가 — 사이트 백지 렌더 수정 [8/8 S] | Frontend | 8 | 8 | S | CSP unsafe-inline 추가 (백지 렌더 수정) |
| 2026-07-05 | Jin | 9ca4170 | fix(infra): nginx /api/v1/ 한정 + NEXT_PUBLIC_API_URL /api/v1 추가 — OAuth/API 라우팅 근본 수정 [9/9 S] | Infra | 9 | 9 | S | OAuth/API 라우팅 근본 수정 (/api/v1) |
| 2026-07-05 | Jin | 20cf2d0 | fix(frontend): OAuth 콜백 리다이렉트 호스트 수정 — publicOrigin 헬퍼 [9/9 S] | Frontend | 9 | 9 | S | OAuth 콜백 리다이렉트 호스트 수정 |
| 2026-07-05 | Jin | dbeb462 | fix(frontend): 온보딩 완료 후 대시보드 경쟁 조건 수정 — goDashboard refresh await [9/9 S] | Frontend | 9 | 9 | S | 온보딩 완료 후 대시보드 경쟁 조건 수정 |
| 2026-07-05 | Jin | fc3fce0 | chore(specs): Approved 스펙 2개 Done 전환 — stage3-embedding-backfill·reanalyze-after-charset [2/8 S] | Docs | 2 | 8 | S | Approved 스펙 2개 Done 전환 |
| 2026-07-05 | Jin | 45379be | feat(stocks): 주가 백필 PARTIAL 상태 도입 + 리뷰 4건 수정 [5/9 M] | Backend | 5 | 9 | M | 주가 백필 PARTIAL 상태 + 리뷰 수정 |
<!-- SCORES -->

## 기간별 집계

<!-- SUMMARY:START -->
| 멤버 | 오늘 커밋 | 7일 커밋 | 30일 커밋 | 평균 품질 | 평균 중요도 |
|------|-----------|----------|-----------|----------|------------|
| Jin | 8 | 31 | 120 | 8.4 | 6.5 |
<!-- SUMMARY:END -->
