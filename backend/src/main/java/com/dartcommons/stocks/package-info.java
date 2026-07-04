/**
 * 종목 마스터 도메인 (코스피200 + 코스닥150 = 약 350종목).
 *
 * <p>{@code stocks} 테이블(V2)을 SSOT로 관리. 공시 커버 종목 필터(disclosure)와
 * portfolios FK의 공통 마스터. 초기 시드는 V10 마이그레이션, 분기 갱신은 {@code StockMasterSyncJob}.
 *
 * <p>표준 하위 패키지(services/repositories/entities)는 기능 구현 시 생성한다(빈 폴더 금지).
 * 의존 방향: shared → 도메인(역방향 금지), 도메인 간 통신은 이벤트 경유(CLAUDE.md 3-2).
 *
 * @see <a href="file:../../../../../../docs/개발명세서/feature_structure.md">feature_structure §4</a>
 */
package com.dartcommons.stocks;
