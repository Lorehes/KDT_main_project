/**
 * 알림 디스패처 도메인.
 *
 * <p>영향 사용자 조회(portfolios 역조회) → 자격 판정(필터/거래시간외/티어) → 빈도 분기(즉시/다이제스트) →
 * 채널 발송(카카오 1순위 → 텔레그램/이메일 폴백) → 결과 기록·재시도(최대 3회, 지수 백오프).
 * 발송 멱등 {@code uq_notification_dedup(user, disclosure, channel)}.
 *
 * <p>표준 하위 패키지는 기능 구현 시 생성(빈 폴더 금지).
 * 의존 방향: shared → 도메인(역방향 금지), 도메인 간 통신은 이벤트 경유(CLAUDE.md 3-2).
 */
package com.dartcommons.notification;
