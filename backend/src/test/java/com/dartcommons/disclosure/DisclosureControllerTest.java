package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] GET /api/v1/disclosures — scope/ORDER BY/Tier 차등 회귀 테스트.
 * [이유] be-api-blocking-bugs-fix R1·R2·R3·R6 수정 검증 — Testcontainers PostgreSQL로 실 DB 검증(Mock DB 금지, CLAUDE.md §6-6).
 *       scope=portfolio/all 분기, 빈 포트폴리오 빈 페이지, ORDER BY rcept_dt DESC, Tier별 analysis 필드 화이트리스트.
 * [사이드 임팩트] 각 테스트는 고유 이메일 + UUID rcept_no로 데이터 간섭 없음.
 *               V10 seed 종목(005930·000660)을 FK 참조 — 해당 스탁코드가 stocks 테이블에 존재해야 함.
 * [수정 시 고려사항] sentiment 메모리 필터 테스트는 fe-correctness-investor-protection Spec에서 추가.
 *                  scope=all 티어 제한(R4/security-hardening-mvp) 이후 테스트 케이스 추가 필요.
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
class DisclosureControllerTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    private String uniqueEmail() {
        return "dct-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String uniqueRceptNo() {
        return "2025" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private long insertDisclosure(String rceptNo, String stockCode, String rceptDt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO disclosures (rcept_no, corp_code, stock_code, corp_name, report_nm, rcept_dt, disclosure_type, collected_at) " +
                "VALUES (?, '00000001', ?, '테스트회사', '테스트공시', ?::date, 'A001', now()) RETURNING id",
                Long.class, rceptNo, stockCode, rceptDt);
        return Objects.requireNonNull(id, "insertDisclosure: DB가 id를 반환하지 않음 (rcept_no=" + rceptNo + ")");
    }

    private void insertAnalysis(long disclosureId) {
        jdbc.update(
                "INSERT INTO analysis_results (disclosure_id, sentiment, confidence, is_withheld, summary, expected_reaction, rationale, stage_reached) " +
                "VALUES (?, 'POSITIVE', 0.850, false, '긍정적 공시입니다.', 'UP', '근거 내용', 2)",
                disclosureId);
    }

    private String signupAndGetToken(String email) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",           email)
                .put("password",        "Password1!")
                .put("nickname",        "테스터")
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

    private String loginAndGetToken(String email) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",    email)
                .put("password", "Password1!");
        String resp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).get("access_token").asText();
    }

    private void addPortfolio(String token, String stockCode) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("stock_code", stockCode);
        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("scope=portfolio — 포트폴리오 등록 종목 공시만 반환 (000660 제외)")
    void list_scopePortfolio_returnsOnlyPortfolioStocks() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");

        insertDisclosure(uniqueRceptNo(), "005930", "2025-06-01");
        insertDisclosure(uniqueRceptNo(), "000660", "2025-06-01");

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(resp).get("content");
        assertThat(content.size()).isGreaterThanOrEqualTo(1);
        for (JsonNode item : content) {
            assertThat(item.get("stock_code").asText()).isEqualTo("005930");
        }
    }

    @Test
    @DisplayName("scope=all — 포트폴리오 무관하게 전체 공시 반환")
    void list_scopeAll_returnsAllDisclosures() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        // 포트폴리오 등록 없이도 scope=all은 전체 반환
        insertDisclosure(uniqueRceptNo(), "005930", "2025-06-02");
        insertDisclosure(uniqueRceptNo(), "000660", "2025-06-02");

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("scope", "all"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(resp).get("content");
        // 두 종목 이상 포함
        assertThat(content.size()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("빈 포트폴리오 scope=portfolio — 빈 페이지 즉시 반환 (DB 조회 없음)")
    void list_emptyPortfolio_returnsEmptyPage() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        // 포트폴리오 미등록

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(resp);
        assertThat(page.get("content").size()).isZero();
        assertThat(page.get("page").get("total_elements").asLong()).isZero();
    }

    @Test
    @DisplayName("ORDER BY rcept_dt DESC — 최신 공시가 이전 공시보다 앞에 정렬")
    void list_scopeAll_orderByRceptDtDesc() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        insertDisclosure(uniqueRceptNo(), "005930", "2024-01-01"); // 오래된
        insertDisclosure(uniqueRceptNo(), "005930", "2025-12-31"); // 최신

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("scope", "all")
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(resp).get("content");
        // 연속된 두 항목의 rcept_dt가 내림차순(같거나 이전)인지 확인
        for (int i = 0; i < content.size() - 1; i++) {
            String curr = content.get(i).get("rcept_dt").asText();
            String next = content.get(i + 1).get("rcept_dt").asText();
            assertThat(curr.compareTo(next)).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    @DisplayName("FREE 티어 분석 결과 — expected_reaction·rationale 미포함, disclaimer 포함")
    void analysis_freeTier_excludesProFields() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        long disclosureId = insertDisclosure(uniqueRceptNo(), "005930", "2025-06-03");
        insertAnalysis(disclosureId);

        String resp = mockMvc.perform(get("/api/v1/disclosures/" + disclosureId + "/analysis")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(resp);
        // 기본 필드 포함
        assertThat(body.has("sentiment")).isTrue();
        assertThat(body.has("confidence")).isTrue();
        assertThat(body.has("summary")).isTrue();
        assertThat(body.has("disclaimer")).isTrue();
        assertThat(body.get("disclaimer").asText()).contains("투자 자문");
        assertThat(body.has("report_inaccuracy_path")).isTrue();
        // Pro+ 필드 제외
        assertThat(body.has("expected_reaction")).isFalse();
        assertThat(body.has("rationale")).isFalse();
    }

    @Test
    @DisplayName("PRO 티어 분석 결과 — expected_reaction·rationale 포함, disclaimer 포함")
    void analysis_proTier_includesProFields() throws Exception {
        String email = uniqueEmail();
        signupAndGetToken(email); // FREE로 가입
        jdbc.update("UPDATE users SET tier = 'PRO' WHERE email = ?", email);
        String proToken = loginAndGetToken(email); // PRO JWT 재발급

        long disclosureId = insertDisclosure(uniqueRceptNo(), "005930", "2025-06-04");
        insertAnalysis(disclosureId);

        String resp = mockMvc.perform(get("/api/v1/disclosures/" + disclosureId + "/analysis")
                        .header("Authorization", "Bearer " + proToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode body = objectMapper.readTree(resp);
        assertThat(body.has("expected_reaction")).isTrue();
        assertThat(body.get("expected_reaction").asText()).isEqualTo("UP");
        assertThat(body.has("rationale")).isTrue();
        assertThat(body.has("disclaimer")).isTrue();
    }

    @Test
    @DisplayName("분석 미완료 공시 — 404 반환")
    void analysis_noResult_returns404() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        long disclosureId = insertDisclosure(uniqueRceptNo(), "005930", "2025-06-05");
        // analysis_results 미삽입

        mockMvc.perform(get("/api/v1/disclosures/" + disclosureId + "/analysis")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
    }
}
