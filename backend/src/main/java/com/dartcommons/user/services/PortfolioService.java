package com.dartcommons.user.services;

import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.shared.enums.Tier;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import com.dartcommons.user.dto.PortfolioRequest;
import com.dartcommons.user.dto.PortfolioResponse;
import com.dartcommons.user.entities.PortfolioEntity;
import com.dartcommons.user.repositories.PortfolioRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * [목적] 포트폴리오 CRUD 서비스 — Free 3종목 제한 + AES-256-GCM 암복호 + IDOR 방지 + corp_name 조회.
 *       create/delete 시 @CacheEvict("portfolioStockCodes")로 UserStockCodesProviderImpl 캐시를 즉시 무효화.
 * [이유] 매수가·수량은 금융 개인정보(CLAUDE.md §7) — 서비스 계층에서 암호화 후 리포지토리에 전달.
 *       IDOR 방지: 모든 조회·수정·삭제에 userId 스코프 쿼리(findByIdAndUserId) 사용.
 *       Free 제한: JWT claims의 tier를 컨트롤러에서 전달받아 서비스 계층에서 검증.
 *       listPortfolios()는 N+1 방지를 위해 stockCode Set을 모아 findByStockCodeIn() 1회 호출.
 * [사이드 임팩트] StockRepository를 read-only로 직접 참조 — 마스터 도메인 예외(CLAUDE.md §3-2).
 *               portfolios.stock_code는 stocks.stock_code FK(DB RESTRICT) — 미등록 종목 저장 시 DB 에러.
 *               애플리케이션 계층에서 existsByStockCode()로 사전 검증해 DB 에러 대신 400 반환.
 *               avgBuyPrice/quantity가 null이면 암호화 없이 null byte[] 저장 — avg_buy_price_enc/quantity_enc는 nullable(V3).
 *               @CacheEvict 위치: create/delete만(stock_code 변경). update는 avg_buy_price·quantity·memo만 변경 — evict 불필요.
 * [수정 시 고려사항] Free→Pro 업그레이드 시 countByUserId() 재검사 불필요(이미 등록된 종목 유지).
 *                  동시 요청 경쟁 조건: @Transactional + DB UNIQUE(user_id, stock_code) 이중 방어.
 *                  avg_buy_price·quantity는 DB 정렬/집계 불가 — 손익계산은 복호화 후 앱 계층에서만.
 *                  listPortfolios() bulk IN 절은 portfolios 행이 수천 건 이상이면 페이지네이션 도입 검토.
 */
@Service
@Transactional
public class PortfolioService {

    private static final int FREE_TIER_LIMIT = 3;

    private final PortfolioRepository portfolioRepository;
    private final StockRepository     stockRepository;
    private final AesGcmEncryptor     encryptor;

    public PortfolioService(PortfolioRepository portfolioRepository,
                            StockRepository stockRepository,
                            AesGcmEncryptor encryptor) {
        this.portfolioRepository = portfolioRepository;
        this.stockRepository     = stockRepository;
        this.encryptor           = encryptor;
    }

    @Transactional(readOnly = true)
    public List<PortfolioResponse> listPortfolios(Long userId) {
        List<PortfolioEntity> entities = portfolioRepository.findByUserId(userId);
        if (entities.isEmpty()) return List.of();
        Set<String> stockCodes = entities.stream()
                .map(PortfolioEntity::getStockCode)
                .collect(Collectors.toSet());
        Map<String, String> corpNameMap = stockRepository.findByStockCodeIn(stockCodes).stream()
                .collect(Collectors.toMap(Stock::getStockCode, Stock::getCorpName));
        return entities.stream()
                .map(e -> toResponse(e, corpNameMap.get(e.getStockCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(Long userId, Long portfolioId) {
        return toResponse(findOwned(userId, portfolioId));
    }

    @CacheEvict(value = "portfolioStockCodes", key = "#userId")
    public PortfolioResponse createPortfolio(Long userId, PortfolioRequest request, Tier tier) {
        if (tier == Tier.FREE && portfolioRepository.countByUserId(userId) >= FREE_TIER_LIMIT) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Free 티어는 최대 " + FREE_TIER_LIMIT + "개 종목만 등록 가능합니다");
        }
        if (portfolioRepository.existsByUserIdAndStockCode(userId, request.stockCode())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 등록된 종목입니다");
        }
        // findById로 존재 확인 + corpName 조회를 1회 쿼리로 통합 (existsByStockCode 대체)
        String corpName = stockRepository.findById(request.stockCode())
                .map(Stock::getCorpName)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 종목코드입니다"));

        PortfolioEntity portfolio = PortfolioEntity.builder()
                .userId(userId)
                .stockCode(request.stockCode())
                .avgBuyPriceEnc(request.avgBuyPrice() != null
                        ? encryptor.encrypt(request.avgBuyPrice().toPlainString()) : null)
                .quantityEnc(request.quantity() != null
                        ? encryptor.encrypt(request.quantity().toPlainString()) : null)
                .memo(request.memo())
                .build();
        portfolioRepository.save(portfolio);
        return toResponse(portfolio, corpName);
    }

    public PortfolioResponse updatePortfolio(Long userId, Long portfolioId, PortfolioRequest request) {
        PortfolioEntity portfolio = findOwned(userId, portfolioId);
        portfolio.update(
                request.avgBuyPrice() != null
                        ? encryptor.encrypt(request.avgBuyPrice().toPlainString()) : null,
                request.quantity() != null
                        ? encryptor.encrypt(request.quantity().toPlainString()) : null,
                request.memo()
        );
        return toResponse(portfolio);    // 단건 toResponse() → findById() 1회 DB 조회
    }

    @CacheEvict(value = "portfolioStockCodes", key = "#userId")
    public void deletePortfolio(Long userId, Long portfolioId) {
        PortfolioEntity portfolio = findOwned(userId, portfolioId);
        portfolioRepository.delete(portfolio);
    }

    /** IDOR 방지: userId 스코프로 조회 — 타인 포트폴리오 접근 시 403. */
    private PortfolioEntity findOwned(Long userId, Long portfolioId) {
        return portfolioRepository.findByIdAndUserId(portfolioId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "해당 포트폴리오에 접근할 권한이 없습니다"));
    }

    /** 단건용 — findById() 1회 stocks 조회 포함. listPortfolios()는 bulk map 오버로드 사용. */
    private PortfolioResponse toResponse(PortfolioEntity e) {
        String corpName = stockRepository.findById(e.getStockCode())
                .map(Stock::getCorpName).orElse(null);
        return toResponse(e, corpName);
    }

    private PortfolioResponse toResponse(PortfolioEntity e, String corpName) {
        String decryptedPrice = encryptor.decrypt(e.getAvgBuyPriceEnc());
        String decryptedQty   = encryptor.decrypt(e.getQuantityEnc());
        // avg_buy_price·quantity 복호화 값은 절대 로그 출력 금지 — 금융 개인정보(CLAUDE.md §7)
        return new PortfolioResponse(
                e.getId(),
                e.getStockCode(),
                corpName,
                decryptedPrice != null ? new BigDecimal(decryptedPrice) : null,
                decryptedQty   != null ? new BigDecimal(decryptedQty)   : null,
                e.getMemo(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }
}
