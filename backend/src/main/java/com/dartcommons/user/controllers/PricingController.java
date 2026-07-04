package com.dartcommons.user.controllers;

import com.dartcommons.shared.config.PricingProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/*
 * [목적] GET /api/v1/pricing/plans — 티어별 요금제 정보 반환. SecurityConfig에 PUBLIC 등록됨.
 * [이유] 비로그인 사용자도 요금제 페이지에서 플랜을 조회할 수 있어야 함.
 *       PricingProperties(yml 바인딩)는 애플리케이션 시작 시 메모리에 올라와 있어 별도 캐시 불필요.
 * [사이드 임팩트] yml pricing.plans 변경 + 재기동 → 즉시 반영. FE는 staleTime: 60_000 권장.
 * [수정 시 고려사항] 투자 권유 표현 금지(CLAUDE.md §7) — features·recommended_for 문구 검토.
 *                  가격 변경 빈도 높아지면 Admin API + DB 방식으로 이관.
 */
@RestController
@RequestMapping("/api/v1/pricing")
public class PricingController {

    private final PricingProperties pricingProperties;

    public PricingController(PricingProperties pricingProperties) {
        this.pricingProperties = pricingProperties;
    }

    @GetMapping("/plans")
    public List<PlanResponse> getPlans() {
        return pricingProperties.plans().stream()
                .map(plan -> new PlanResponse(
                        plan.tier(),
                        plan.price(),
                        plan.currency(),
                        plan.features(),
                        plan.recommendedFor(),
                        plan.monthlyFreeQuota()))
                .toList();
    }

    public record PlanResponse(
            String tier,
            long price,
            String currency,
            List<String> features,
            @JsonProperty("recommended_for")     String recommendedFor,
            @JsonProperty("monthly_free_quota")  int monthlyFreeQuota
    ) {}
}
