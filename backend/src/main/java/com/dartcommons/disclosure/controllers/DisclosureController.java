package com.dartcommons.disclosure.controllers;

import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.services.AnalysisQueryService;
import com.dartcommons.disclosure.dto.DisclosureListItemResponse;
import com.dartcommons.disclosure.services.DisclosureQueryService;
import com.dartcommons.shared.dto.PageResponse;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.shared.enums.Tier;
import com.dartcommons.shared.security.SecurityUtils;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;

/*
 * [목적] GET /api/v1/disclosures 목록 + GET /api/v1/disclosures/{id} 상세 + GET /api/v1/disclosures/{id}/analysis.
 * [이유] 공시 피드는 사용자 보유 종목 기반 개인화(scope=portfolio 기본) — JWT userId 필수.
 *       분析 결과는 공시의 하위 자원으로 설계(REST 계층 구조) — 동일 컨트롤러로 묶어 URL 계층 명시.
 * [사이드 임팩트] 분析 결과 티어 차등은 AnalysisQueryService.getByDisclosureId() 내부에서 처리.
 *               SecurityConfig에서 /api/v1/disclosures/** 는 authenticated() — JWT 없으면 401.
 * [수정 시 고려사항] sort 파라미터는 현재 rceptDt,desc 고정 — 다양한 정렬 추가 시 Pageable Sort 파라미터 수용.
 *                  POST /disclosures (공시 등록)는 admin 전용 — DisclosureBackfillController로 분리됨.
 *                  extractTier는 SecurityUtils로 이관 완료(R3) — 신규 컨트롤러도 동일 유틸 사용.
 *                  q @Size(max=100): @Validated + jakarta.validation 어노테이션으로 컨트롤러 레이어에서 검증.
 *                  빈 문자열("") → null 정규화는 DisclosureQueryService 진입부에서 처리(책임 분리).
 */
@RestController
@RequestMapping("/api/v1/disclosures")
@Validated
public class DisclosureController {

    private final DisclosureQueryService disclosureQueryService;
    private final AnalysisQueryService   analysisQueryService;

    public DisclosureController(DisclosureQueryService disclosureQueryService,
                                AnalysisQueryService analysisQueryService) {
        this.disclosureQueryService = disclosureQueryService;
        this.analysisQueryService   = analysisQueryService;
    }

    /** 공시 목록 — scope/sentiment/날짜/종목코드 조건부 필터. */
    @GetMapping
    public PageResponse<DisclosureListItemResponse> list(
            @AuthenticationPrincipal Long userId,
            Authentication authentication,
            @RequestParam(defaultValue = "portfolio") String scope,
            @RequestParam(value = "stock_code", required = false) @Pattern(regexp = "^[0-9]{6}$") String stockCode,
            @RequestParam(required = false) Sentiment sentiment,
            @RequestParam(required = false) Boolean withheld,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") @Positive @Max(100) int size,
            @RequestParam(required = false) @Size(max = 100) String q
    ) {
        Tier tier = SecurityUtils.extractTier(authentication);
        return disclosureQueryService.list(userId, scope, stockCode, sentiment, withheld, from, to, page, size, tier, q);
    }

    /** 공시 상세 — 기본 메타 + 분析 요약 포함. 인증만 필요 (DART 공개 데이터 정책). */
    @GetMapping("/{id}")
    public DisclosureListItemResponse detail(@PathVariable Long id) {
        return disclosureQueryService.detail(id);
    }

    /** 공시 분析 결과 — 티어별 필드 차등. 분析 미완료 시 404. 포트폴리오 미소유 시 404(IDOR 방어). */
    @GetMapping("/{id}/analysis")
    public AnalysisResponse analysis(
            @PathVariable Long id,
            @AuthenticationPrincipal Long userId,
            Authentication authentication
    ) {
        if (!disclosureQueryService.hasPortfolioAccess(userId, id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "공시를 찾을 수 없습니다.");
        }
        Tier tier = SecurityUtils.extractTier(authentication);
        return analysisQueryService.getByDisclosureId(id, tier);
    }
}
