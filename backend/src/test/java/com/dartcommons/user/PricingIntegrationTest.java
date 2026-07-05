package com.dartcommons.user;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] GET /api/v1/pricing/plans 통합 테스트 — PUBLIC 엔드포인트 응답 스키마 검증.
 * [이유] 비로그인 사용자도 접근 가능해야 함(SecurityConfig PUBLIC 등록). yml 바인딩(PricingProperties) 검증.
 *       투자 권유 표현 금지(CLAUDE.md §7) — features·recommended_for 문구 수동 검토 게이트 역할.
 * [사이드 임팩트] 없음 — 읽기 전용. Testcontainers PostgreSQL은 Flyway 마이그레이션 실행에만 사용.
 * [수정 시 고려사항] yml pricing.plans 티어 수 변경 시 테스트 수정 필요.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
})
class PricingIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    @Test
    @DisplayName("요금제 조회 — 인증 없이 200 OK (PUBLIC 엔드포인트)")
    void getPlans_noAuth_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/pricing/plans"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("요금제 3개 반환 — FREE / PRO / PREMIUM")
    void getPlans_returnsThreeTiers() throws Exception {
        String resp = mockMvc.perform(get("/api/v1/pricing/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode plans = objectMapper.readTree(resp);
        assertThat(plans.isArray()).isTrue();
        assertThat(plans.size()).isEqualTo(3);

        Set<String> tiers = new HashSet<>();
        plans.forEach(p -> tiers.add(p.get("tier").asText()));
        assertThat(tiers).containsExactlyInAnyOrder("FREE", "PRO", "PREMIUM");
    }

    @Test
    @DisplayName("각 요금제 필수 필드 포함 — tier·price·currency·features·recommended_for·monthly_free_quota·recent_window_days")
    void getPlans_eachPlanHasRequiredFields() throws Exception {
        String resp = mockMvc.perform(get("/api/v1/pricing/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode plans = objectMapper.readTree(resp);
        for (JsonNode plan : plans) {
            assertThat(plan.has("tier")).isTrue();
            assertThat(plan.get("tier").asText()).isNotBlank();

            assertThat(plan.has("price")).isTrue();
            assertThat(plan.get("price").asLong()).isGreaterThanOrEqualTo(0);

            assertThat(plan.has("currency")).isTrue();
            assertThat(plan.get("currency").asText()).isEqualTo("KRW");

            assertThat(plan.has("features")).isTrue();
            assertThat(plan.get("features").isArray()).isTrue();
            assertThat(plan.get("features").size()).isGreaterThan(0);

            assertThat(plan.has("recommended_for")).isTrue();
            assertThat(plan.get("recommended_for").asText()).isNotBlank();

            assertThat(plan.has("monthly_free_quota")).isTrue();
            assertThat(plan.get("monthly_free_quota").asInt()).isGreaterThanOrEqualTo(0);

            // 날짜 축 정책(tier-policy-config-api) — 0=무제한, 양수=최근 N일 클램프
            assertThat(plan.has("recent_window_days")).isTrue();
            assertThat(plan.get("recent_window_days").asInt()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("티어 날짜 창 단일 소스 — FREE recent_window_days=5(클램프와 일치), PRO/PREMIUM=0(무제한)")
    void getPlans_recentWindowDays_freeIsClampSource() throws Exception {
        // 단일 소스 검증: DisclosureQueryService Free 클램프(5일)와 /pricing/plans FREE 창이 같은 yml 값에서 파생.
        // DisclosureQueryServiceIntegrationTest의 5일 경계 테스트와 짝을 이뤄 BE↔노출값 정합을 양방향 고정.
        String resp = mockMvc.perform(get("/api/v1/pricing/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode plans = objectMapper.readTree(resp);
        int freeWindow = -1, proWindow = -1, premiumWindow = -1;
        for (JsonNode plan : plans) {
            switch (plan.get("tier").asText()) {
                case "FREE"    -> freeWindow    = plan.get("recent_window_days").asInt();
                case "PRO"     -> proWindow     = plan.get("recent_window_days").asInt();
                case "PREMIUM" -> premiumWindow = plan.get("recent_window_days").asInt();
                default -> { /* 알 수 없는 티어 무시 */ }
            }
        }
        assertThat(freeWindow).as("FREE 창은 클램프 정책(5일)과 일치해야 함").isEqualTo(5);
        assertThat(proWindow).as("PRO는 날짜 클램프 없음").isZero();
        assertThat(premiumWindow).as("PREMIUM은 날짜 클램프 없음").isZero();
    }

    @Test
    @DisplayName("FREE 플랜 가격 = 0, PREMIUM 플랜 가격 > 0")
    void getPlans_freePriceZero_premiumPricePositive() throws Exception {
        String resp = mockMvc.perform(get("/api/v1/pricing/plans"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode plans = objectMapper.readTree(resp);
        long freePrice = -1, premiumPrice = -1;
        for (JsonNode plan : plans) {
            String tier = plan.get("tier").asText();
            if ("FREE".equals(tier))    freePrice    = plan.get("price").asLong();
            if ("PREMIUM".equals(tier)) premiumPrice = plan.get("price").asLong();
        }
        assertThat(freePrice).isEqualTo(0);
        assertThat(premiumPrice).isGreaterThan(0);
    }
}
