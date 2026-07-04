package com.dartcommons.user.controllers;

import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.PageRequest;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/*
 * [목적] 종목 검색 공개 엔드포인트 — GET /api/v1/stocks/search?q= (인증 불필요).
 * [이유] 포트폴리오 등록 전 종목 검색은 인증 여부와 무관(SecurityConfig permitAll 등록).
 *       StockRepository는 마스터 도메인(CLAUDE.md §3-2) — user 컨트롤러에서 read-only 직접 의존 허용.
 * [사이드 임팩트] q="" 빈 검색 방지(@NotBlank) — 전체 종목 반환 방지.
 * [수정 시 고려사항] 검색 트래픽이 높아지면 StockRepository.search()에 @Cacheable("stockSearch") 추가 고려.
 *                  corpus가 350종목이므로 현재는 캐시 없이도 응답 충분.
 */
@RestController
@RequestMapping("/api/v1/stocks")
@Validated
public class StockSearchController {

    private static final int MAX_RESULTS = 20;

    private final StockRepository stockRepository;

    public StockSearchController(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @GetMapping("/search")
    public List<StockSearchItem> search(
            @RequestParam @NotBlank(message = "검색어를 입력하세요") @Size(max = 50) String q) {
        return stockRepository.search(q, PageRequest.of(0, MAX_RESULTS)).stream()
                .map(StockSearchItem::from)
                .toList();
    }

    public record StockSearchItem(
            @JsonProperty("stock_code") String stockCode,
            @JsonProperty("corp_name")  String corpName,
            String market,
            String sector
    ) {
        public static StockSearchItem from(Stock s) {
            return new StockSearchItem(s.getStockCode(), s.getCorpName(), s.getMarket(), s.getSector());
        }
    }
}
