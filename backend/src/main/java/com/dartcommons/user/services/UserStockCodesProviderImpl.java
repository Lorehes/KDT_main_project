package com.dartcommons.user.services;

import com.dartcommons.shared.ports.UserStockCodesPort;
import com.dartcommons.user.repositories.PortfolioRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/*
 * [목적] UserStockCodesPort 구현체 — PortfolioRepository 위임으로 user 도메인 내부에서 처리.
 * [이유] PortfolioRepository는 user 도메인 내부 컴포넌트. 외부 도메인(disclosure/analysis)이 직접 의존하면
 *       user 도메인 내부 변경이 외부 도메인 컴파일 의존성을 오염시킴.
 *       인터페이스를 shared/ports에, 구현체를 user/services에 두어 import 방향을 단방향으로 고정.
 * [사이드 임팩트] @Transactional(readOnly=true) — 단독 호출 시 읽기 전용 최적화.
 *               FeedbackService(write tx) 컨텍스트에서 호출 시 REQUIRED 전파로 write tx에 참여 — readOnly 무시됨.
 * [수정 시 고려사항] getStockCodes는 findStockCodesByUserId 스칼라 프로젝션 사용 — avg_buy_price_enc 등 로드 없음.
 *                  existsByUserIdAndStockCode는 복합 인덱스(user_id, stock_code) 사용.
 */
@Service
@RequiredArgsConstructor
public class UserStockCodesProviderImpl implements UserStockCodesPort {

    private final PortfolioRepository portfolioRepository;

    @Override
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
