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
| 2026-06-07 | Jin | c0fca6f | feat(user): M2 이메일 Auth 서비스+컨트롤러+DTO Wave 2 [9/8 M] | User/Shared | 9 | 8 | M | signup/login/refresh/logout 구현 |
<!-- SCORES -->

## 기간별 집계

<!-- SUMMARY:START -->
| 멤버 | 오늘 커밋 | 7일 커밋 | 30일 커밋 | 평균 품질 | 평균 중요도 |
|------|-----------|----------|-----------|----------|------------|
| Jin | 2 | 2 | 8 | 7.9 | 7.5 |
<!-- SUMMARY:END -->
