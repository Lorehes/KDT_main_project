package com.dartcommons.user.repositories;

import com.dartcommons.user.entities.PortfolioEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/*
 * [목적] portfolios 테이블 CRUD — Free 3종목 제한·IDOR 방지를 위한 userId 스코프 쿼리.
 * [이유] stock_code 역조회(공시→영향 사용자)와 사용자별 목록 조회가 핵심 패턴.
 * [사이드 임팩트] findByStockCode는 NotificationDispatcher(feature_structure §2)가 사용할 경로.
 *               현재는 user 도메인에 두되, 알림 도메인 도입 시 shared 이벤트 경유 리팩터링 검토.
 * [수정 시 고려사항] 알림 발송(stock_code 역조회)은 대량 사용자 집합 조회 가능 — 페이지네이션 고려.
 */
public interface PortfolioRepository extends JpaRepository<PortfolioEntity, Long> {

    List<PortfolioEntity> findByUserId(Long userId);

    /** 종목코드 목록 스칼라 프로젝션 — avg_buy_price_enc 등 암호화 컬럼 로드 없이 stock_code만 반환. */
    @Query("SELECT p.stockCode FROM PortfolioEntity p WHERE p.userId = :userId")
    List<String> findStockCodesByUserId(@Param("userId") Long userId);

    Optional<PortfolioEntity> findByIdAndUserId(Long id, Long userId);

    boolean existsByUserIdAndStockCode(Long userId, String stockCode);

    long countByUserId(Long userId);

    /** 공시 → 영향 사용자 역조회 (NotificationDispatcher 경로 — 후속 notification 도메인 이관 검토). */
    List<PortfolioEntity> findByStockCode(String stockCode);
}
