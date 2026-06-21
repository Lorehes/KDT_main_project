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

    @BeforeEach
    void bypassEmailVerification() {
        when(emailVerificationService.isEmailVerified(anyString())).thenReturn(true);
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
}
