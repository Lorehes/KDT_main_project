/**
 * 공시 수집·룰 분류 도메인 (Stage 1).
 *
 * <p>DART OpenAPI 1분 폴링 수신 → 메타 추출 → 공시 유형 룰 분류 → 커버 종목 필터 →
 * {@code disclosures} 멱등 적재(rcept_no UNIQUE). 분석 트리거 이벤트 발행.
 *
 * <p>표준 하위 패키지(controllers/services/repositories/entities/dto)는 기능 구현 시 생성한다(빈 폴더 금지).
 * 의존 방향: shared → 도메인(역방향 금지), 도메인 간 통신은 이벤트 경유(CLAUDE.md 3-2).
 *
 * @see <a href="file:../../../../../../docs/개발명세서/feature_structure.md">feature_structure</a>
 */
package com.dartcommons.disclosure;
