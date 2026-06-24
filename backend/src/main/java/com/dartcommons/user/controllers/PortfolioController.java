package com.dartcommons.user.controllers;

import com.dartcommons.shared.enums.Tier;
import com.dartcommons.shared.security.SecurityUtils;
import com.dartcommons.user.dto.PortfolioRequest;
import com.dartcommons.user.dto.PortfolioResponse;
import com.dartcommons.user.dto.PortfolioSummaryResponse;
import com.dartcommons.user.services.PortfolioService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/*
 * [목적] 포트폴리오 CRUD REST 엔드포인트 — /api/v1/portfolios.
 * [이유] 매수가·수량은 응답 직렬화 전 서비스 계층에서 복호화 — 컨트롤러는 PortfolioResponse만 반환.
 *       tier는 JWT 토큰에 ROLE_{tier} 권한으로 포함 — DB 조회 없이 SecurityContext에서 추출.
 * [사이드 임팩트] POST 시 Free 3종목 제한(tier=FREE), 중복 stockCode(409), 미지원 stockCode(400) 검사.
 *               GET/{id}/PUT/{id}/DELETE/{id}: IDOR 방지(403) — userId 스코프 쿼리.
 * [수정 시 고려사항] 대량 포트폴리오 목록이 필요하면 GET /portfolios에 페이지네이션 추가.
 *                  tier 기반 제한을 DB 레벨로 내리려면 TRIGGER 또는 CHECK 제약 추가 필요.
 */
@RestController
@RequestMapping("/api/v1/portfolios")
public class PortfolioController {

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /** 평가 손익 집계 — literal "summary"로 /{id}(Long 타입)와 충돌 없음. */
    @GetMapping("summary")
    public PortfolioSummaryResponse summary(@AuthenticationPrincipal Long userId) {
        return portfolioService.summarize(userId);
    }

    @GetMapping
    public List<PortfolioResponse> list(@AuthenticationPrincipal Long userId) {
        return portfolioService.listPortfolios(userId);
    }

    @GetMapping("/{id}")
    public PortfolioResponse get(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        return portfolioService.getPortfolio(userId, id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PortfolioResponse create(@AuthenticationPrincipal Long userId,
                                    Authentication authentication,
                                    @Valid @RequestBody PortfolioRequest request) {
        Tier tier = SecurityUtils.extractTier(authentication);
        return portfolioService.createPortfolio(userId, request, tier);
    }

    @PutMapping("/{id}")
    public PortfolioResponse update(@AuthenticationPrincipal Long userId,
                                    @PathVariable Long id,
                                    @Valid @RequestBody PortfolioRequest request) {
        return portfolioService.updatePortfolio(userId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        portfolioService.deletePortfolio(userId, id);
    }

}

