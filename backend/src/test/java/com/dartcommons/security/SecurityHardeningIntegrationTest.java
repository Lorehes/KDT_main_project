package com.dartcommons.security;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/*
 * [목적] security-hardening-mvp 카드 #14 통합 테스트 — CORS·size 제한·JWT 위변조·Swagger 가드 회귀 검증.
 * [이유] 코드 구현(R5·R9·R11·R7)이 실제로 올바르게 동작하는지 Testcontainers 실 DB + MockMvc로 검증.
 *       각 케이스는 독립 테스트 — 한 케이스가 실패해도 나머지 보안 게이트 회귀를 감지할 수 있음.
 * [사이드 임팩트] CORS 테스트를 위해 dartcommons.allowed-origins=http://localhost:3000 설정 필요.
 *               JWT 위변조 테스트는 실제로 WARN 로그가 발생하므로 테스트 로그에서 확인 가능.
 * [수정 시 고려사항] ALLOWED_ORIGINS 변경 시 CORS 테스트의 Origin 값도 맞게 갱신.
 *                  @Max(100) 한도 변경 시 size 경계 케이스 테스트도 함께 갱신.
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
        "dartcommons.allowed-origins=http://localhost:3000",
        "dartcommons.llm.provider=mock"
})
class SecurityHardeningIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    // ─── CORS (R5) ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CORS preflight — 허용 Origin에서 OPTIONS 200 + Access-Control-Allow-Origin 반환 (R5)")
    void cors_preflight_allowedOrigin_returns200() throws Exception {
        mockMvc.perform(options("/api/v1/disclosures")
                        .header(HttpHeaders.ORIGIN,                          "http://localhost:3000")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,   "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS,  "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"));
    }

    @Test
    @DisplayName("CORS preflight — 미허용 Origin은 Access-Control-Allow-Origin 없음 (R5 안전 실패)")
    void cors_preflight_disallowedOrigin_noAllowOriginHeader() throws Exception {
        mockMvc.perform(options("/api/v1/disclosures")
                        .header(HttpHeaders.ORIGIN,                         "https://evil.example.com")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD,  "GET"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // ─── size 파라미터 제한 (R9) ─────────────────────────────────────────────────

    @Test
    @DisplayName("size=99999 — @Max(100) 위반으로 400 BadRequest (R9)")
    void list_sizeOverMax_returns400() throws Exception {
        String token = signupAndGetToken();

        mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "99999"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("size=100 — @Max(100) 경계값 허용 (R9)")
    void list_sizeAtMax_returns200() throws Exception {
        String token = signupAndGetToken();

        mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk());
    }

    // ─── JWT 위변조 (R11) ───────────────────────────────────────────────────────

    @Test
    @DisplayName("JWT 위변조 토큰 — 401 Unauthorized (R11 + WARN 로그 발생)")
    void forgeredJwt_returns401() throws Exception {
        // 서명이 다른 임의 JWT-like 문자열 (BASE64 인코딩된 헤더.페이로드.위조서명)
        String forgedToken = "eyJhbGciOiJIUzI1NiJ9"
                + ".eyJzdWIiOiIxIiwidGllciI6IkZSRUUifQ"
                + ".invalid_signature_here";

        mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + forgedToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("JWT 없음 — 인증 필요 엔드포인트에서 401 (R11)")
    void noJwt_authenticatedEndpoint_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/disclosures"))
                .andExpect(status().isUnauthorized());
    }

    // ─── Swagger UI 가드 (R7) ───────────────────────────────────────────────────

    @Test
    @DisplayName("Swagger UI — 자격증명 없이 접근 시 401 (R7: admin chain HTTP Basic 가드)")
    void swaggerUi_withoutCredentials_returns401() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("OpenAPI docs — 자격증명 없이 접근 시 401 (R7: admin chain)")
    void openApiDocs_withoutCredentials_returns401() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isUnauthorized());
    }

    // ─── 헬퍼 ──────────────────────────────────────────────────────────────────

    private String signupAndGetToken() throws Exception {
        String email = "sht-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",            email)
                .put("password",         "Password1!")
                .put("nickname",         "보안테스터")
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
}
