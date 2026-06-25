package com.dartcommons.user.services;

import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.shared.enums.Tier;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.services.StockMasterService;
import com.dartcommons.stocks.services.StockPriceProvider;
import com.dartcommons.user.dto.PortfolioRequest;
import com.dartcommons.user.dto.PortfolioResponse;
import com.dartcommons.user.dto.PortfolioSummaryResponse;
import com.dartcommons.user.entities.PortfolioEntity;
import com.dartcommons.user.repositories.PortfolioRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
 *       listPortfolios()는 N+1 방지를 위해 stockCode Set을 모아 StockMasterService.findByStockCodeIn() 1회 캐시 조회.
 * [사이드 임팩트] StockMasterService(캐시 위임)를 통해 종목 마스터 read-only 접근 — 마스터 도메인 예외(CLAUDE.md §3-2).
 *               user → stocks read-only 의존은 허용: stocks는 마스터 데이터 도메인으로 안정적이며 모든 도메인의 공통 기준 데이터.
 *               portfolios.stock_code는 stocks.stock_code FK(DB RESTRICT) — 미등록 종목 저장 시 DB 에러.
 *               애플리케이션 계층에서 stockMasterService.findByStockCode()로 사전 검증해 DB 에러 대신 400 반환.
 *               avgBuyPrice/quantity가 null이면 암호화 없이 null byte[] 저장 — avg_buy_price_enc/quantity_enc는 nullable(V3).
 *               @CacheEvict 위치: create/delete만(stock_code 변경). update는 avg_buy_price·quantity·memo만 변경 — evict 불필요.
 * [수정 시 고려사항] Free→Pro 업그레이드 시 countByUserId() 재검사 불필요(이미 등록된 종목 유지).
 *                  동시 요청 경쟁 조건: @Transactional + DB UNIQUE(user_id, stock_code) 이중 방어.
 *                  avg_buy_price·quantity는 DB 정렬/집계 불가 — 손익계산은 복호화 후 앱 계층에서만.
 *                  listPortfolios() bulk IN 절은 portfolios 행이 수천 건 이상이면 페이지네이션 도입 검토.
 *                  summarize()는 복호화 후 즉시 계산 — 중간 값 절대 로그 출력 금지.
 */
@Service
@Transactional
public class PortfolioService {

    private static final int FREE_TIER_LIMIT = 3;

    private final PortfolioRepository portfolioRepository;
    private final StockMasterService  stockMasterService;
    private final StockPriceProvider  stockPriceProvider;
    private final AesGcmEncryptor     encryptor;

    public PortfolioService(PortfolioRepository portfolioRepository,
                            StockMasterService stockMasterService,
                            StockPriceProvider stockPriceProvider,
                            AesGcmEncryptor encryptor) {
        this.portfolioRepository = portfolioRepository;
        this.stockMasterService  = stockMasterService;
        this.stockPriceProvider  = stockPriceProvider;
        this.encryptor           = encryptor;
    }

    /**
     * 전체 포트폴리오 평가 손익 집계.
     * close_price + avgBuyPrice/quantity가 모두 있는 종목만 totalCostBasis·totalEvalAmount에 합산.
     * 복호화 중간 값은 절대 로그 출력 금지 — 금융 개인정보(CLAUDE.md §7).
     */
    @Transactional(readOnly = true)
    public PortfolioSummaryResponse summarize(Long userId) {
        List<PortfolioEntity> portfolios = portfolioRepository.findByUserId(userId);
        if (portfolios.isEmpty()) {
            return new PortfolioSummaryResponse(
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, 0, 0, null);
        }

        Set<String> stockCodes = portfolios.stream()
                .map(PortfolioEntity::getStockCode)
                .collect(Collectors.toSet());
        Map<String, StockPriceProvider.PriceInfo> priceMap = stockPriceProvider.findLatestPrices(stockCodes);

        BigDecimal totalCostBasis  = BigDecimal.ZERO;
        BigDecimal totalEvalAmount = BigDecimal.ZERO;
        int pricedCount   = 0;
        int unpricedCount = 0;
        LocalDate asOf    = null;

        for (PortfolioEntity p : portfolios) {
            String decryptedPrice = encryptor.decrypt(p.getAvgBuyPriceEnc());
            String decryptedQty   = encryptor.decrypt(p.getQuantityEnc());
            // 복호화 값은 변수에 보관 후 즉시 계산 — 절대 로그 출력 금지
            BigDecimal buyPrice = parseSafe(decryptedPrice);
            BigDecimal qty      = parseSafe(decryptedQty);

            if (buyPrice == null || qty == null) {
                unpricedCount++;
                continue;
            }
            StockPriceProvider.PriceInfo pi = priceMap.get(p.getStockCode());
            if (pi == null) {
                unpricedCount++;
                continue;
            }
            totalCostBasis  = totalCostBasis.add(buyPrice.multiply(qty));
            totalEvalAmount = totalEvalAmount.add(pi.closePrice().multiply(qty));
            pricedCount++;
            if (asOf == null || pi.priceAsof().isAfter(asOf)) {
                asOf = pi.priceAsof();
            }
        }

        BigDecimal totalPnl = totalEvalAmount.subtract(totalCostBasis);
        BigDecimal pnlRate  = null;
        if (totalCostBasis.compareTo(BigDecimal.ZERO) != 0) {
            pnlRate = totalPnl.divide(totalCostBasis, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
        }

        return new PortfolioSummaryResponse(
                totalCostBasis, totalEvalAmount, totalPnl, pnlRate,
                pricedCount, unpricedCount, asOf);
    }

    @Transactional(readOnly = true)
    public List<PortfolioResponse> listPortfolios(Long userId) {
        List<PortfolioEntity> entities = portfolioRepository.findByUserId(userId);
        if (entities.isEmpty()) return List.of();
        Set<String> stockCodes = entities.stream()
                .map(PortfolioEntity::getStockCode)
                .collect(Collectors.toSet());
        Map<String, String> corpNameMap = stockMasterService.findByStockCodeIn(stockCodes).stream()
                .collect(Collectors.toMap(Stock::getStockCode, Stock::getCorpName, (a, b) -> a));
        return entities.stream()
                .map(e -> toResponse(e, corpNameMap.get(e.getStockCode())))
                .toList();
    }

    @Transactional(readOnly = true)
    public PortfolioResponse getPortfolio(Long userId, Long portfolioId) {
        PortfolioEntity e = findOwned(userId, portfolioId);
        String corpName = stockMasterService.findByStockCode(e.getStockCode())
                .map(Stock::getCorpName).orElse(null);
        return toResponse(e, corpName);
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
        String corpName = stockMasterService.findByStockCode(request.stockCode())
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
        String corpName = stockMasterService.findByStockCode(portfolio.getStockCode())
                .map(Stock::getCorpName).orElse(null);
        return toResponse(portfolio, corpName);
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

    private PortfolioResponse toResponse(PortfolioEntity e, String corpName) {
        String decryptedPrice = encryptor.decrypt(e.getAvgBuyPriceEnc());
        String decryptedQty   = encryptor.decrypt(e.getQuantityEnc());
        // avg_buy_price·quantity 복호화 값은 절대 로그 출력 금지 — 금융 개인정보(CLAUDE.md §7)
        return new PortfolioResponse(
                e.getId(),
                e.getStockCode(),
                corpName,
                parseSafe(decryptedPrice),
                parseSafe(decryptedQty),
                e.getMemo(),
                e.getCreatedAt(),
                e.getUpdatedAt()
        );
    }

    /** 복호화 결과 BigDecimal 파싱 — NFE 발생 시 원문을 예외 메시지에 절대 포함하지 않음(금융 PII, CLAUDE.md §7). */
    static BigDecimal parseSafe(String decrypted) {
        if (decrypted == null) return null;
        try {
            return new BigDecimal(decrypted);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("암호화 필드 복호화 값 파싱 실패");
        }
    }
}
