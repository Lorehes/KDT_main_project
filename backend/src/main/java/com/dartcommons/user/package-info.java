/**
 * 사용자 도메인 — 인증·보유종목·알림설정·동의·티어.
 *
 * <p>가입/로그인/OAuth(Kakao·Google·Naver)+JWT(Spring Security), 보유종목 CRUD(매수가/수량 AES-256),
 * 알림 설정(채널/빈도/필터/거래시간외), 동의 이력(consent_logs INSERT-only), Free/Pro/Premium 등급.
 *
 * <p>표준 하위 패키지는 기능 구현 시 생성(빈 폴더 금지).
 * 의존 방향: shared → 도메인(역방향 금지), 도메인 간 통신은 이벤트 경유(CLAUDE.md 3-2).
 */
package com.dartcommons.user;
