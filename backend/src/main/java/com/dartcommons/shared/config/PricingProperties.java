package com.dartcommons.shared.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/*
 * [목적] application.yml pricing.plans 배열을 타입 안전 record로 바인딩 — GET /api/v1/pricing/plans 응답 데이터 소스.
 * [이유] 가격 정보 코드 하드코딩 금지(CLAUDE.md §7). yml에서 관리하면 코드 수정·재배포 없이 운영 환경에서 변경 가능.
 *       DB(system_configs) 대신 yml 선택 이유: 가격 변경 빈도 낮음(월~분기) + 멀티 인스턴스 동기화 불필요.
 * [사이드 임팩트] yml 수정 후 재기동 필요(JVM 인메모리 바인딩). 무중단 변경이 필요하면 Admin API + DB 이관 필요.
 * [수정 시 고려사항] 투자 권유 표현 금지(CLAUDE.md §7) — features·recommended_for 문구 검토 필수.
 *                  currency는 "KRW"로 고정(해외 결제 도입 시 필드 확장).
 */
@ConfigurationProperties("pricing")
@Validated
public record PricingProperties(List<Plan> plans) {

    public record Plan(
            @NotBlank String tier,
            @PositiveOrZero long price,
            @DefaultValue("KRW") String currency,
            @NotNull List<String> features,
            @DefaultValue("") String recommendedFor,
            @DefaultValue("0") int monthlyFreeQuota
    ) {}
}
