package com.dartcommons.user;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] 이메일 인증 E2E 통합 테스트 — signup/login/refresh rotation/logout + 경계 케이스.
 * [이유] Testcontainers PostgreSQL로 실 DB 검증(Mock DB 금지 — CLAUDE.md §6-6).
 *       JWT access/refresh token 발급·무효화를 HTTP 계층에서 end-to-end 검증.
 * [사이드 임팩트] 각 테스트는 UUID 이메일로 독립 실행 — BeforeEach 삭제 없이 간섭 없음.
 *               DisclosurePollingJob·DisclosureBackfillService mock으로 DART API 호출 차단.
 * [수정 시 고려사항] access token 블랙리스트 도입 시 logout 후 protected 엔드포인트 검증 확장.
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
class AuthIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "auth-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private JsonNode doSignup(String email) throws Exception {
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
        return objectMapper.readTree(resp);
    }

    @Test
    @DisplayName("회원가입 성공 — 201 + access_token·refresh_token 비어있지 않음")
    void signup_success_returns201WithTokens() throws Exception {
        JsonNode resp = doSignup(uniqueEmail());
        assertThat(resp.get("access_token").asText()).isNotBlank();
        assertThat(resp.get("refresh_token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("중복 이메일 재가입 — 409 Conflict")
    void signup_duplicateEmail_returns409() throws Exception {
        String email = uniqueEmail();
        doSignup(email);

        ObjectNode body = objectMapper.createObjectNode()
                .put("email",           email)
                .put("password",        "Password1!")
                .put("nickname",        "중복유저")
                .put("termsAgreed",     true)
                .put("privacyAgreed",   true)
                .put("disclaimerAgreed", true)
                .put("marketingAgreed", false);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("로그인 성공 — 200 + access_token·refresh_token 반환")
    void login_success_returns200WithTokens() throws Exception {
        String email = uniqueEmail();
        doSignup(email);

        ObjectNode body = objectMapper.createObjectNode()
                .put("email",    email)
                .put("password", "Password1!");

        String resp = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode tokens = objectMapper.readTree(resp);
        assertThat(tokens.get("access_token").asText()).isNotBlank();
        assertThat(tokens.get("refresh_token").asText()).isNotBlank();
    }

    @Test
    @DisplayName("잘못된 비밀번호 로그인 — 401 Unauthorized")
    void login_wrongPassword_returns401() throws Exception {
        String email = uniqueEmail();
        doSignup(email);

        ObjectNode body = objectMapper.createObjectNode()
                .put("email",    email)
                .put("password", "WrongPassword!");

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh rotation — 갱신 후 새 토큰 발급, 기존 토큰 재사용 시 401")
    void refresh_rotation_newTokenIssuedAndOldInvalidated() throws Exception {
        JsonNode first = doSignup(uniqueEmail());
        String oldRefresh = first.get("refresh_token").asText();

        ObjectNode body = objectMapper.createObjectNode().put("refresh_token", oldRefresh);

        // 첫 번째 갱신 성공 → 새 refresh token 발급
        String resp = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(resp).get("refresh_token").asText())
                .isNotBlank()
                .isNotEqualTo(oldRefresh);

        // 기존 토큰 재사용 → 401 (rotation으로 무효화됨)
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("로그아웃 — 204, 이후 동일 refresh 토큰 사용 시 401")
    void logout_returns204AndInvalidatesRefreshToken() throws Exception {
        JsonNode tokens = doSignup(uniqueEmail());
        String refresh = tokens.get("refresh_token").asText();

        ObjectNode body = objectMapper.createObjectNode().put("refresh_token", refresh);

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }
}
