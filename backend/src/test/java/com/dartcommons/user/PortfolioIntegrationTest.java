package com.dartcommons.user;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
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
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] 포트폴리오 CRUD + Free 3종목 제한(422) + IDOR(403) + AES-256 DB 암호화 통합 테스트.
 * [이유] Testcontainers PostgreSQL로 실 DB 검증(Mock DB 금지 — CLAUDE.md §6-6).
 *       AES 암호화는 JdbcTemplate로 avg_buy_price_enc BYTEA 직접 조회해 검증.
 * [사이드 임팩트] V10 seed_stocks 마이그레이션 데이터(005930 등)를 종목코드로 사용.
 *               각 테스트는 UUID 이메일 유저를 독립 생성 — 데이터 간섭 없음.
 * [수정 시 고려사항] Pro 티어 제한 해제 검증은 tier DB 직접 업데이트 후 추가 가능.
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
class PortfolioIntegrationTest {

    @MockitoBean DisclosurePollingJob        pollingJob;
    @MockitoBean DisclosureBackfillService   backfillService;
    @MockitoBean EmailVerificationService    emailVerificationService;

    @Autowired MockMvc       mockMvc;
    @Autowired ObjectMapper  objectMapper;
    @Autowired JdbcTemplate  jdbcTemplate;
    @Autowired CacheManager  cacheManager;

    @BeforeEach
    void bypassEmailVerification() {
        when(emailVerificationService.isEmailVerified(anyString())).thenReturn(true);
    }

    @BeforeEach
    void resetStockPrices() {
        jdbcTemplate.update("UPDATE stocks SET close_price = NULL, price_asof = NULL");
        cacheManager.getCache("stockByCode").clear();
        cacheManager.getCache("stocksByCodeIn").clear();
    }

    private String uniqueEmail() {
        return "pf-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String signupAndGetToken(String email) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",           email)
                .put("password",        "Password1!")
                .put("nickname",        "포트테스터")
                .put("termsAgreed",     true)
                .put("privacyAgreed",   true)
                .put("disclaimerAgreed", true)
                .put("marketingAgreed", false);

        String resp = mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("access_token").asText();
    }

    private JsonNode createPortfolio(String token, String stockCode) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("stock_code",    stockCode)
                .put("avg_buy_price", new BigDecimal("50000"))
                .put("quantity",      new BigDecimal("10"))
                .put("memo",          "테스트 메모");

        String resp = mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp);
    }

    @Test
    @DisplayName("포트폴리오 생성 — 201 + id·stock_code·corp_name·avg_buy_price 반환 (단건 조회 경로)")
    void createPortfolio_success_returns201WithFields() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        JsonNode resp = createPortfolio(token, "005930");

        assertThat(resp.get("id").asLong()).isPositive();
        assertThat(resp.get("stock_code").asText()).isEqualTo("005930");
        assertThat(resp.get("corp_name").asText()).isEqualTo("삼성전자");
        assertThat(resp.get("avg_buy_price").decimalValue())
                .isEqualByComparingTo(new BigDecimal("50000"));
    }

    @Test
    @DisplayName("포트폴리오 생성 — 종가 수집된 종목은 close_price·price_asof 반환 (Wave 3 매수가 박스)")
    void createPortfolio_withClosePrice_returnsPriceFields() throws Exception {
        // 종가 수집 상황 재현 — resetStockPrices()가 NULL로 초기화하므로 이 종목만 세팅
        jdbcTemplate.update("UPDATE stocks SET close_price = 74400, price_asof = DATE '2026-07-02' WHERE stock_code = '005930'");
        String token = signupAndGetToken(uniqueEmail());

        JsonNode resp = createPortfolio(token, "005930");

        assertThat(resp.get("close_price").decimalValue()).isEqualByComparingTo(new BigDecimal("74400"));
        assertThat(resp.get("price_asof").asText()).isEqualTo("2026-07-02");
    }

    @Test
    @DisplayName("포트폴리오 생성 — 종가 미수집 종목은 close_price null (손익 박스 미노출 폴백)")
    void createPortfolio_withoutClosePrice_nullPriceFields() throws Exception {
        // resetStockPrices()로 close_price=NULL 상태 — 별도 세팅 없음
        String token = signupAndGetToken(uniqueEmail());

        JsonNode resp = createPortfolio(token, "005930");

        assertThat(resp.get("close_price").isNull()).isTrue();
        assertThat(resp.get("price_asof").isNull()).isTrue();
    }

    @Test
    @DisplayName("포트폴리오 목록 조회 — 200 + 2종목 corp_name bulk 조회 경로 검증")
    void listPortfolios_success_returns200() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930");
        createPortfolio(token, "000660");

        String resp = mockMvc.perform(get("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode list = objectMapper.readTree(resp);
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(2);
        assertThat(StreamSupport.stream(list.spliterator(), false)
                .map(n -> n.get("corp_name").asText())
                .toList())
                .containsExactlyInAnyOrder("삼성전자", "SK하이닉스");
    }

    @Test
    @DisplayName("포트폴리오 수정 — 200 + 변경된 avgBuyPrice 반환")
    void updatePortfolio_success_returns200() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        long id = createPortfolio(token, "005930").get("id").asLong();

        ObjectNode body = objectMapper.createObjectNode()
                .put("stock_code",    "005930")
                .put("avg_buy_price", new BigDecimal("60000"))
                .put("quantity",      new BigDecimal("5"))
                .put("memo",          "수정 메모");

        String resp = mockMvc.perform(put("/api/v1/portfolios/" + id)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(resp).get("avg_buy_price").decimalValue())
                .isEqualByComparingTo(new BigDecimal("60000"));
    }

    @Test
    @DisplayName("포트폴리오 삭제 — 204 No Content + 이후 목록 비어있음")
    void deletePortfolio_returns204AndRemovedFromList() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        long id = createPortfolio(token, "005930").get("id").asLong();

        mockMvc.perform(delete("/api/v1/portfolios/" + id)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        String resp = mockMvc.perform(get("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(resp).size()).isZero();
    }

    @Test
    @DisplayName("Free 티어 3종목 초과 — 4번째 등록 시 422 Unprocessable Entity")
    void createPortfolio_freeTierExceeded_returns422() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        // 3종목까지 성공
        createPortfolio(token, "005930");
        createPortfolio(token, "000660");
        createPortfolio(token, "402340");

        // 4번째 → 422
        ObjectNode body = objectMapper.createObjectNode()
                .put("stock_code",    "005380")
                .put("avg_buy_price", new BigDecimal("100000"))
                .put("quantity",      new BigDecimal("1"));

        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("타인 포트폴리오 단건 조회 — IDOR 방지 403 Forbidden")
    void getPortfolio_idor_returns403() throws Exception {
        String tokenA = signupAndGetToken(uniqueEmail());
        long portfolioIdA = createPortfolio(tokenA, "005930").get("id").asLong();

        String tokenB = signupAndGetToken(uniqueEmail());
        mockMvc.perform(get("/api/v1/portfolios/" + portfolioIdA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("타인 포트폴리오 삭제 — IDOR 방지 403 Forbidden")
    void deletePortfolio_idor_returns403() throws Exception {
        String tokenA = signupAndGetToken(uniqueEmail());
        long portfolioIdA = createPortfolio(tokenA, "005930").get("id").asLong();

        String tokenB = signupAndGetToken(uniqueEmail());
        mockMvc.perform(delete("/api/v1/portfolios/" + portfolioIdA)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("AES-256 암호화 — DB의 avg_buy_price_enc·quantity_enc가 암호화된 바이트로 저장됨")
    void createPortfolio_sensitiveFieldsEncryptedInDb() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        long portfolioId = createPortfolio(token, "005930").get("id").asLong();

        // AES-GCM 저장 형식: IV(12 bytes) ‖ ciphertext+tag — 최소 28 bytes 이상
        byte[] priceEnc = jdbcTemplate.queryForObject(
                "SELECT avg_buy_price_enc FROM portfolios WHERE id = ?",
                byte[].class, portfolioId);
        byte[] qtyEnc = jdbcTemplate.queryForObject(
                "SELECT quantity_enc FROM portfolios WHERE id = ?",
                byte[].class, portfolioId);

        assertThat(priceEnc).isNotNull().hasSizeGreaterThan(12);
        assertThat(qtyEnc).isNotNull().hasSizeGreaterThan(12);
    }

    // ── R1: summary 엔드포인트 ────────────────────────────────────────────────

    @Test
    @DisplayName("summary — 포트폴리오 없음 → 200 + 전체 0 값, as_of·pnl_rate null")
    void summary_noPortfolios_returns200WithZeros() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        String resp = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("priced_count").asInt()).isZero();
        assertThat(json.get("unpriced_count").asInt()).isZero();
        assertThat(json.get("total_cost_basis").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(json.get("total_pnl").decimalValue()).isEqualByComparingTo(BigDecimal.ZERO);
        JsonNode asOf = json.get("as_of");
        assertThat(asOf == null || asOf.isNull()).isTrue();
        JsonNode pnlRate = json.get("pnl_rate");
        assertThat(pnlRate == null || pnlRate.isNull()).isTrue();
    }

    @Test
    @DisplayName("summary — 응답 필드 snake_case 회귀 방지 (@JsonProperty 검증)")
    void summary_snakeCaseFieldNames_verified() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        String resp = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.has("total_cost_basis")).isTrue();
        assertThat(json.has("totalCostBasis")).isFalse();
        assertThat(json.has("total_eval_amount")).isTrue();
        assertThat(json.has("priced_count")).isTrue();
        assertThat(json.has("unpriced_count")).isTrue();
    }

    @Test
    @DisplayName("summary — 종가 DB 직접 주입 후 집계 수학적 정확성 검증")
    void summary_withClosePriceViaDb_aggregatesCorrectly() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930"); // avgBuyPrice=50000, quantity=10

        jdbcTemplate.update(
                "UPDATE stocks SET close_price = ?, price_asof = CURRENT_DATE WHERE stock_code = '005930'",
                new BigDecimal("60000"));

        String resp = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("priced_count").asInt()).isEqualTo(1);
        assertThat(json.get("unpriced_count").asInt()).isZero();
        assertThat(json.get("total_cost_basis").decimalValue()).isEqualByComparingTo(new BigDecimal("500000"));
        assertThat(json.get("total_eval_amount").decimalValue()).isEqualByComparingTo(new BigDecimal("600000"));
        assertThat(json.get("total_pnl").decimalValue()).isEqualByComparingTo(new BigDecimal("100000"));
        assertThat(json.get("pnl_rate").decimalValue()).isEqualByComparingTo(new BigDecimal("20"));
    }

    @Test
    @DisplayName("summary — 종가 NULL 종목 → unpriced_count=1")
    void summary_nullClosePrice_countsAsUnpriced() throws Exception {
        // @BeforeEach에서 이미 NULL로 초기화됨
        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930");

        String resp = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("unpriced_count").asInt()).isEqualTo(1);
        assertThat(json.get("priced_count").asInt()).isZero();
    }

    @Test
    @DisplayName("summary — avgBuyPrice/quantity 미입력(null) 포트폴리오 → unpriced_count=1")
    void summary_nullAvgBuyPrice_countsAsUnpriced() throws Exception {
        jdbcTemplate.update(
                "UPDATE stocks SET close_price = 60000, price_asof = CURRENT_DATE WHERE stock_code = '005930'");
        String token = signupAndGetToken(uniqueEmail());

        // avgBuyPrice/quantity 없이 POST
        ObjectNode body = objectMapper.createObjectNode().put("stock_code", "005930");
        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());

        String resp = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("unpriced_count").asInt()).isEqualTo(1);
        assertThat(json.get("priced_count").asInt()).isZero();
    }

    @Test
    @DisplayName("summary — 종가있는 종목 1 + 종가없는 종목 1 → priced=1, unpriced=1")
    void summary_mixedPortfolios_splitCounts() throws Exception {
        jdbcTemplate.update(
                "UPDATE stocks SET close_price = 60000, price_asof = CURRENT_DATE WHERE stock_code = '005930'");
        // 000660은 @BeforeEach에서 NULL로 초기화됨

        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930"); // priced (close_price=60000, avgBuyPrice=50000)
        createPortfolio(token, "000660"); // unpriced (close_price=NULL)

        String resp = mockMvc.perform(get("/api/v1/portfolios/summary")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("priced_count").asInt()).isEqualTo(1);
        assertThat(json.get("unpriced_count").asInt()).isEqualTo(1);
    }

    // ── 그룹 A: 캐시 검증 ────────────────────────────────────────────────────

    // ── 벌크 임포트 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("bulkImport — 2개 유효 + 1개 미지원 → added 2·skipped_unsupported 1·나머지 0")
    void bulkImport_mixedCodes_classifiesCorrectly() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        // 005930(삼성전자), 000660(SK하이닉스) — V10 seed_stocks에 존재
        // INVALID000 — 마스터에 없음 → skipped_unsupported
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("stock_codes").add("005930").add("000660").add("INVALID000");

        String resp = mockMvc.perform(post("/api/v1/portfolios/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("added").size()).isEqualTo(2);
        assertThat(json.get("skipped_unsupported").size()).isEqualTo(1);
        assertThat(json.get("skipped_duplicate").size()).isZero();
        assertThat(json.get("skipped_limit").size()).isZero();
    }

    @Test
    @DisplayName("bulkImport — 이미 등록된 코드 포함 → skipped_duplicate에 분류")
    void bulkImport_duplicateCode_classifiedAsSkippedDuplicate() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930");  // 사전 등록

        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("stock_codes").add("005930").add("000660");

        String resp = mockMvc.perform(post("/api/v1/portfolios/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("added").size()).isEqualTo(1);
        assertThat(json.get("skipped_duplicate").size()).isEqualTo(1);
        assertThat(json.get("skipped_duplicate").get(0).asText()).isEqualTo("005930");
    }

    @Test
    @DisplayName("bulkImport — Free 티어 2종목 등록 후 3번째+만 추가 시 한도 초과 분류")
    void bulkImport_freeTierLimit_skipsOverQuota() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930");
        createPortfolio(token, "000660");
        // 402340(삼성SDS)·005380(현대차) — 3번째 슬롯 1개만, 나머지 1개는 skipped_limit

        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("stock_codes").add("402340").add("005380");

        String resp = mockMvc.perform(post("/api/v1/portfolios/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode json = objectMapper.readTree(resp);

        assertThat(json.get("added").size()).isEqualTo(1);
        assertThat(json.get("skipped_limit").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("bulkImport — 빈 배열 → 400 Bad Request")
    void bulkImport_emptyList_returns400() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        ObjectNode body = objectMapper.createObjectNode();
        body.putArray("stock_codes"); // 빈 배열 → @NotEmpty 위반 → 400

        mockMvc.perform(post("/api/v1/portfolios/import")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    // ── 그룹 A: 캐시 검증 ────────────────────────────────────────────────────

    @Test
    @DisplayName("listPortfolios 2회 연속 호출 — stocksByCodeIn 캐시 히트 (Caffeine)")
    void listPortfolios_secondCall_hitsCacheNotDb() throws Exception {
        // 캐시 초기화 (테스트 격리, @BeforeEach에서 이미 수행되지만 명시)
        cacheManager.getCache("stocksByCodeIn").clear();

        String token = signupAndGetToken(uniqueEmail());
        createPortfolio(token, "005930");

        // 1번째 호출 — 캐시 미스 → DB
        mockMvc.perform(get("/api/v1/portfolios").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // 캐시에 값이 적재됐는지 검증 (Caffeine NativeCache 접근)
        org.springframework.cache.Cache stockCache = cacheManager.getCache("stocksByCodeIn");
        assertThat(stockCache).isNotNull();
        // 캐시 내부 map 사이즈 > 0 이면 히트 가능 상태
        @SuppressWarnings("unchecked")
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeCache =
                (com.github.benmanes.caffeine.cache.Cache<Object, Object>) stockCache.getNativeCache();
        assertThat(nativeCache.estimatedSize()).isGreaterThan(0);

        // 2번째 호출 — 캐시 히트해도 응답은 동일
        mockMvc.perform(get("/api/v1/portfolios").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
