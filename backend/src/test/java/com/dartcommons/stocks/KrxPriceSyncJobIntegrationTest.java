package com.dartcommons.stocks;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.infrastructure.krx.KrxClient;
import com.dartcommons.user.services.EmailVerificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] KrxPriceSyncJob.syncPrices() 통합 테스트 — DB 갱신·캐시 evict·이상치 필터(±50%)·최초 적재 경계 검증.
 * [이유] Testcontainers PostgreSQL 실 DB 검증(Mock DB 금지 — CLAUDE.md §6-6). eval-pnl-integration-tests Spec R2+R2-추가.
 * [사이드 임팩트] stocks.close_price 컬럼 직접 변경. @BeforeEach reset()으로 각 케이스 시작 전 전체 초기화.
 * [수정 시 고려사항] ANOMALY_THRESHOLD(±50%) 조정 시 syncPrices_anomalyPrice_skipsUpdate 경계값 수치 같이 수정.
 *                  syncPrices_evictsCache_freshPriceServedNextQuery: 75000원은 전일 60000 대비 +25%(허용 범위) — 비율 유지 필요.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.krx.price-sync.enabled=true",   // 전역 false override — KrxPriceSyncJob Bean 필요(@Autowired), 실 API는 @MockitoBean KrxClient로 차단
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
})
class KrxPriceSyncJobIntegrationTest {

    @MockitoBean KrxClient              krxClient;
    @MockitoBean DisclosurePollingJob   pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;
    @MockitoBean EmailVerificationService emailVerificationService;

    @Autowired KrxPriceSyncJob krxPriceSyncJob;
    @Autowired JdbcTemplate    jdbcTemplate;
    @Autowired MockMvc         mockMvc;
    @Autowired ObjectMapper    objectMapper;
    @Autowired CacheManager    cacheManager;

    @BeforeEach
    void reset() {
        jdbcTemplate.update("UPDATE stocks SET close_price = NULL, price_asof = NULL");
        cacheManager.getCache("stockByCode").clear();
        cacheManager.getCache("stocksByCodeIn").clear();
    }

    @BeforeEach
    void bypassEmailVerification() {
        when(emailVerificationService.isEmailVerified(anyString())).thenReturn(true);
    }

    private String uniqueEmail() {
        return "krx-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String signupAndGetToken(String email) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",            email)
                .put("password",         "Password1!")
                .put("nickname",         "KRX테스터")
                .put("termsAgreed",      true)
                .put("privacyAgreed",    true)
                .put("disclaimerAgreed", true)
                .put("marketingAgreed",  false);

        String resp = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("access_token").asText();
    }

    private void createPortfolio(String token, String stockCode) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("stock_code",    stockCode)
                .put("avg_buy_price", new BigDecimal("50000"))
                .put("quantity",      new BigDecimal("10"));

        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("syncPrices — KrxClient stub → stocks.close_price·price_asof DB 갱신")
    void syncPrices_updatesClosePrice_inDb() {
        given(krxClient.fetchAllClosePrices()).willReturn(Map.of(
                "005930", new KrxClient.StockCloseInfo(new BigDecimal("60000"), LocalDate.now())
        ));

        krxPriceSyncJob.syncPrices();

        BigDecimal saved = jdbcTemplate.queryForObject(
                "SELECT close_price FROM stocks WHERE stock_code = '005930'", BigDecimal.class);
        assertThat(saved).isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    @DisplayName("syncPrices — 빈 Map stub → 기존 close_price 유지(조기 반환)")
    void syncPrices_emptyPriceMap_doesNotOverwrite() {
        jdbcTemplate.update(
                "UPDATE stocks SET close_price = 50000, price_asof = CURRENT_DATE WHERE stock_code = '005930'");
        given(krxClient.fetchAllClosePrices()).willReturn(Map.of());

        krxPriceSyncJob.syncPrices();

        BigDecimal saved = jdbcTemplate.queryForObject(
                "SELECT close_price FROM stocks WHERE stock_code = '005930'", BigDecimal.class);
        assertThat(saved).isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("syncPrices — 캐시 evict 검증: 잡 실행 후 summary API에 최신 종가 반영")
    void syncPrices_evictsCache_freshPriceServedNextQuery() throws Exception {
        jdbcTemplate.update(
                "UPDATE stocks SET close_price = 60000, price_asof = CURRENT_DATE WHERE stock_code = '005930'");

        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930"); // avgBuyPrice=50000, qty=10

        // 첫 summary 호출 → 캐시 미스 → DB 60000원 반영(eval=600000)
        String resp1 = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json1 = objectMapper.readTree(resp1);
        assertThat(json1.get("total_eval_amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("600000")); // 60000 * 10

        // syncPrices: 75000원(+25%, 허용) stub → DB 갱신 + stocksByCodeIn 캐시 evict
        given(krxClient.fetchAllClosePrices()).willReturn(Map.of(
                "005930", new KrxClient.StockCloseInfo(new BigDecimal("75000"), LocalDate.now())
        ));
        krxPriceSyncJob.syncPrices();

        // 두 번째 summary 호출 → 캐시 evict 후 DB 75000원 반영(eval=750000)
        String resp2 = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json2 = objectMapper.readTree(resp2);
        assertThat(json2.get("total_eval_amount").decimalValue())
                .isEqualByComparingTo(new BigDecimal("750000")); // 75000 * 10
    }

    @Test
    @DisplayName("syncPrices — 전일비 ±50% 초과(4999원) 이상치 스킵 / ±40%(6000원) 정상 변동 허용")
    void syncPrices_anomalyPrice_skipsUpdate() {
        // 전일 10000원 → 4999원(±50.01% 초과, 이상치) → DB 미갱신
        jdbcTemplate.update(
                "UPDATE stocks SET close_price = 10000, price_asof = CURRENT_DATE WHERE stock_code = '005930'");
        given(krxClient.fetchAllClosePrices()).willReturn(Map.of(
                "005930", new KrxClient.StockCloseInfo(new BigDecimal("4999"), LocalDate.now())
        ));
        krxPriceSyncJob.syncPrices();

        BigDecimal afterAnomaly = jdbcTemplate.queryForObject(
                "SELECT close_price FROM stocks WHERE stock_code = '005930'", BigDecimal.class);
        assertThat(afterAnomaly).isEqualByComparingTo(new BigDecimal("10000")); // 변경 없음

        // 전일 10000원 → 6000원(±40%, 허용) → DB 갱신
        jdbcTemplate.update(
                "UPDATE stocks SET close_price = 10000, price_asof = CURRENT_DATE WHERE stock_code = '005930'");
        given(krxClient.fetchAllClosePrices()).willReturn(Map.of(
                "005930", new KrxClient.StockCloseInfo(new BigDecimal("6000"), LocalDate.now())
        ));
        krxPriceSyncJob.syncPrices();

        BigDecimal afterNormal = jdbcTemplate.queryForObject(
                "SELECT close_price FROM stocks WHERE stock_code = '005930'", BigDecimal.class);
        assertThat(afterNormal).isEqualByComparingTo(new BigDecimal("6000")); // 갱신됨
    }

    @Test
    @DisplayName("syncPrices — close_price NULL(최초 적재) → 어떤 가격이든 UPDATE 허용")
    void syncPrices_nullPrevPrice_alwaysAllowed() {
        // @BeforeEach reset()에서 이미 NULL로 초기화됨
        given(krxClient.fetchAllClosePrices()).willReturn(Map.of(
                "005930", new KrxClient.StockCloseInfo(new BigDecimal("60000"), LocalDate.now())
        ));

        krxPriceSyncJob.syncPrices();

        BigDecimal saved = jdbcTemplate.queryForObject(
                "SELECT close_price FROM stocks WHERE stock_code = '005930'", BigDecimal.class);
        assertThat(saved).isEqualByComparingTo(new BigDecimal("60000"));
    }
}
