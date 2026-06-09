package com.dartcommons.disclosure.controllers;

import com.dartcommons.analysis.dto.AnalysisResponse;
import com.dartcommons.analysis.services.AnalysisQueryService;
import com.dartcommons.disclosure.services.DisclosureListItemResponse;
import com.dartcommons.disclosure.services.DisclosureQueryService;
import com.dartcommons.shared.dto.PageResponse;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.user.entities.UserEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Comparator;

/*
 * [목적] GET /api/v1/disclosures 목록 + GET /api/v1/disclosures/{id} 상세 + GET /api/v1/disclosures/{id}/analysis.
 * [이유] 공시 피드는 사용자 보유 종목 기반 개인화(scope=portfolio 기본) — JWT userId 필수.
 *       분석 결과는 공시의 하위 자원으로 설계(REST 계층 구조) — 동일 컨트롤러로 묶어 URL 계층 명시.
 * [사이드 임팩트] 분석 결과 티어 차등은 AnalysisQueryService.getByDisclosureId() 내부에서 처리.
 *               SecurityConfig에서 /api/v1/disclosures/** 는 authenticated() — JWT 없으면 401.
 * [수정 시 고려사항] sort 파라미터는 현재 rceptDt,desc 고정 — 다양한 정렬 추가 시 Pageable Sort 파라미터 수용.
 *                  POST /disclosures (공시 등록)는 admin 전용 — DisclosureBackfillController로 분리됨.
 */
@RestController
@RequestMapping("/api/v1/disclosures")
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
            @RequestParam(defaultValue = "portfolio") String scope,
            @RequestParam(required = false) String stock_code,
            @RequestParam(required = false) Sentiment sentiment,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return disclosureQueryService.list(userId, scope, stock_code, sentiment, from, to, page, size);
    }

    /** 공시 상세 — 기본 메타 + 분석 요약 포함. */
    @GetMapping("/{id}")
    public DisclosureListItemResponse detail(@PathVariable Long id) {
        return disclosureQueryService.detail(id);
    }

    /** 공시 분석 결과 — 티어별 필드 차등. 분석 미완료 시 404. */
    @GetMapping("/{id}/analysis")
    public AnalysisResponse analysis(
            @PathVariable Long id,
            Authentication authentication
    ) {
        UserEntity.Tier tier = extractTier(authentication);
        return analysisQueryService.getByDisclosureId(id, tier);
    }

    /*
     * [목적] SecurityContext의 authorities에서 가장 높은 Tier를 추출 — FREE/PRO/PREMIUM.
     * [이유] JWT authority는 JwtAuthenticationFilter에서 단일 항목(ROLE_{TIER})으로 세팅됨.
     *       단일 항목이어도 max()는 안전 — 향후 다중 authorities 발급으로 변경 시에도 최고 티어가 보장됨.
     *       findFirst() 대신 max()로 교체: ordinal 비교(FREE=0, PRO=1, PREMIUM=2) — 순서에 의존하지 않는 명시적 우선순위.
     * [사이드 임팩트] PREMIUM 사용자가 기존에 PRO 응답을 받던 edge case 해소.
     * [수정 시 고려사항] Tier enum에 새 값 추가 시 ordinal 순서를 높은 권한 순으로 유지 필수.
     *                  security-hardening-mvp Spec의 SecurityUtils.extractTier()로 이관 예정 — 본 메서드는 그 때 삭제.
     */
    private UserEntity.Tier extractTier(Authentication auth) {
        if (auth == null) return UserEntity.Tier.FREE;
        return auth.getAuthorities().stream()
                .map(a -> a.getAuthority().replace("ROLE_", ""))
                .map(s -> {
                    try { return UserEntity.Tier.valueOf(s); }
                    catch (IllegalArgumentException e) { return UserEntity.Tier.FREE; }
                })
                .max(Comparator.comparingInt(UserEntity.Tier::ordinal))
                .orElse(UserEntity.Tier.FREE);
    }
}
