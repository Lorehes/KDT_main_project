/**
 * 공통 모듈 — 도메인 횡단 관심사.
 *
 * <p>도메인 이벤트, 공통 예외/에러 응답 포맷(api_spec 1-3), AES-256-GCM 암복호 유틸,
 * 장운영 판단(거래시간/주말/공휴일), BaseEntity(생성/수정 시각) 등.
 *
 * <p>중요: 이 모듈은 어떤 도메인도 import 하지 않는다(import 방향 shared → 도메인, 역방향 금지, CLAUDE.md 3-2).
 * 표준 하위 패키지는 기능 구현 시 생성(빈 폴더 금지).
 */
package com.dartcommons.shared;
