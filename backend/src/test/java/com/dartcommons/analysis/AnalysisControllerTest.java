package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.services.AnalysisOrchestrator;
import com.dartcommons.user.services.EmailVerificationService;
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

import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] POST /api/v1/analyses/{id}/feedback — upsert·재투표·소유권·rate-limit 통합 테스트.
 * [이유] FeedbackService의 TOCTOU upsert 패턴·IDOR 방어·rate-limit 로직을 Testcontainers PostgreSQL로 검증(R18).
 *       Mock DB 금지(CLAUDE.md §6-6) — UNIQUE 제약 기반 upsert 패턴은 실 DB 없이 재현 불가.
 * [사이드 임팩트] 각 테스트는 고유 이메일 + UUID rceptNo로 데이터 간섭 없음.
 *               V10 seed 종목(005930)을 FK 참조.
 * [수정 시 고려사항] rate-limit 케이스(30건/시간 초과)는 Caffeine 인메모리 캐시에 의존 — 테스트 내 30건 연속 호출 필요.
 *                  현재는 기본 케이스만 포함. rate-limit 임계치 변경 시 테스트도 갱신.
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
class AnalysisControllerTest {

    @MockitoBean AnalysisOrchestrator        analysisOrchestrator;
    @MockitoBean EmailVerificationService    emailVerificationService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void bypassEmailVerification() {
        when(emailVerificationService.isEmailVerified(anyString())).thenReturn(true);
    }

    private String uniqueEmail() {
        return "act-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String uniqueRceptNo() {
        return "2025" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private String signupAndGetToken(String email) throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",            email)
                .put("password",         "Password1!")
                .put("nickname",         "테스터")
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
        ObjectNode body = objectMapper.createObjectNode().put("stock_code", stockCode);
        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    private long insertDisclosureAndAnalysis(String rceptNo, String stockCode) {
        Long discId = jdbc.queryForObject(
                "INSERT INTO disclosures (rcept_no, corp_code, stock_code, corp_name, report_nm, rcept_dt, disclosure_type, collected_at) " +
                "VALUES (?, '00000001', ?, '테스트회사', '테스트공시', '2025-06-01'::date, 'A001', now()) RETURNING id",
                Long.class, rceptNo, stockCode);
        discId = Objects.requireNonNull(discId);
        Long analysisId = jdbc.queryForObject(
                "INSERT INTO analysis_results (disclosure_id, sentiment, confidence, is_withheld, summary, expected_reaction, rationale, stage_reached) " +
                "VALUES (?, 'POSITIVE', 0.850, false, '테스트 요약', 'UP', '테스트 근거', 2) RETURNING id",
                Long.class, discId);
        return Objects.requireNonNull(analysisId);
    }

    @Test
    @DisplayName("신규 피드백 저장 — 201 대신 204 NO_CONTENT (FeedbackService upsert)")
    void submitFeedback_newInsert_returns204() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");
        long analysisId = insertDisclosureAndAnalysis(uniqueRceptNo(), "005930");

        ObjectNode body = objectMapper.createObjectNode()
                .put("verdict", "USEFUL");
        mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("동일 사용자 재투표 — 204 반환 (upsert UPDATE 경로)")
    void submitFeedback_reVote_returns204() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");
        long analysisId = insertDisclosureAndAnalysis(uniqueRceptNo(), "005930");

        ObjectNode body = objectMapper.createObjectNode().put("verdict", "USEFUL");
        mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());

        // 재투표 — INACCURATE로 변경
        body = objectMapper.createObjectNode().put("verdict", "INACCURATE").put("reason", "분析이 잘못됐습니다");
        mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());

        // DB에서 verdict가 INACCURATE로 갱신됐는지 확인
        String verdict = jdbc.queryForObject(
                "SELECT verdict FROM feedbacks WHERE analysis_id = ? LIMIT 1",
                String.class, analysisId);
        assertThat(verdict).isEqualTo("INACCURATE");
    }

    @Test
    @DisplayName("포트폴리오 미소유 분석 피드백 — 404 반환 (IDOR 방어)")
    void submitFeedback_noPortfolioOwnership_returns404() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        // 포트폴리오에 005930 미등록 상태에서 005930 분析 피드백 시도
        long analysisId = insertDisclosureAndAnalysis(uniqueRceptNo(), "005930");

        ObjectNode body = objectMapper.createObjectNode().put("verdict", "USEFUL");
        mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("존재하지 않는 analysisId 피드백 — 404 반환")
    void submitFeedback_nonExistentAnalysis_returns404() throws Exception {
        String token = signupAndGetToken(uniqueEmail());

        ObjectNode body = objectMapper.createObjectNode().put("verdict", "USEFUL");
        mockMvc.perform(post("/api/v1/analyses/999999999/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("verdict 미포함 요청 — 400 Bad Request (@NotNull 검증)")
    void submitFeedback_missingVerdict_returns400() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");
        long analysisId = insertDisclosureAndAnalysis(uniqueRceptNo(), "005930");

        ObjectNode body = objectMapper.createObjectNode(); // verdict 없음
        mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/feedback")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없는 피드백 — 401 Unauthorized")
    void submitFeedback_noAuth_returns401() throws Exception {
        long analysisId = insertDisclosureAndAnalysis(uniqueRceptNo(), "005930");

        ObjectNode body = objectMapper.createObjectNode().put("verdict", "USEFUL");
        mockMvc.perform(post("/api/v1/analyses/" + analysisId + "/feedback")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }
}
