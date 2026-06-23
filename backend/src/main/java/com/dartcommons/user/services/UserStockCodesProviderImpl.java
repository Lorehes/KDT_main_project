package com.dartcommons.user.services;

import com.dartcommons.shared.ports.UserStockCodesPort;
import com.dartcommons.user.repositories.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * [목적] UserStockCodesPort 구현체 — PortfolioRepository 위임으로 user 도메인 내부에서 처리.
 *       getStockCodes에 @Cacheable("portfolioStockCodes") 적용 — 공시 피드 매 요청마다 portfolios SELECT 제거.
 * [이유] PortfolioRepository는 user 도메인 내부 컴포넌트. 외부 도메인(disclosure/analysis)이 직접 의존하면
 *       user 도메인 내부 변경이 외부 도메인 컴파일 의존성을 오염시킴.
 *       인터페이스를 shared/ports에, 구현체를 user/services에 두어 import 방향을 단방향으로 고정.
 *       캐시 위치: DisclosureQueryService.resolveStockCodes(private)는 @Cacheable 불가 →
 *       cross-bean 호출로 AOP 프록시가 개입하는 이 구현체가 캐시의 올바른 경계.
 * [사이드 임팩트] @Transactional(readOnly=true) — 단독 호출 시 읽기 전용 최적화.
 *               FeedbackService(write tx) 컨텍스트에서 호출 시 REQUIRED 전파로 write tx에 참여 — readOnly 무시됨.
 *               캐시 TTL 5분 동안 portfolios 변경이 캐시에 반영 안 될 수 있음 —
 *               PortfolioService create/delete @CacheEvict로 즉시 무효화(CacheConfig 참조).
 *               scope=all·명시 stockCode 경로는 getStockCodes를 호출하지 않음 — 캐시 우회됨(정상).
 * [수정 시 고려사항] getStockCodes는 findStockCodesByUserId 스칼라 프로젝션 사용 — avg_buy_price_enc 등 로드 없음.
 *                  existsByUserIdAndStockCode는 복합 인덱스(user_id, stock_code) 사용.
 *                  CacheConfig.java의 portfolioStockCodes maximumSize=1_000 — 동시 사용자 초과 시 조정 필요.
 */
@Service
@RequiredArgsConstructor
public class UserStockCodesProviderImpl implements UserStockCodesPort {

    private final PortfolioRepository portfolioRepository;

    @Override
    @Cacheable(value = "portfolioStockCodes", key = "#userId")
    @Transactional(readOnly = true)
    public List<String> getStockCodes(Long userId) {
        return portfolioRepository.findStockCodesByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasStockCode(Long userId, String stockCode) {
        return portfolioRepository.existsByUserIdAndStockCode(userId, stockCode);
    }
}
