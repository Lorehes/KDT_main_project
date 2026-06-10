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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] 동의 이력 REST 통합 테스트 — getStatus·재동의 기록·버전 불일치·인증 게이트.
 * [이유] consent_logs는 법적 증거 — INSERT-only 불변 이력. Testcontainers PostgreSQL로 실 DB 검증.
 *       ConsentService.CURRENT_POLICY_VERSION("v1.0")과 signup 레코드 일치 여부를 DB 쿼리 단에서 확인.
 * [사이드 임팩트] 각 테스트 사용자는 uniqueEmail()로 독립 생성.
 *               AuthService.signup() → recordSignupConsents() → consent_logs 4건 INSERT됨.
 * [수정 시 고려사항] CURRENT_POLICY_VERSION 변경 시 getStatus 관련 테스트 재검토 필요.
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
class ConsentIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "consent-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String signupAndGetToken() throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",            uniqueEmail())
                .put("password",         "Password1!")
                .put("nickname",         "동의테스터")
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

    @Test
    @DisplayName("회원가입 직후 동의 상태 조회 — requires_renewal=false (현재 버전 동의됨)")
    void getStatus_afterSignup_requiresRenewalFalse() throws Exception {
        String token = signupAndGetToken();

        String resp = mockMvc.perform(get("/api/v1/consents/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode status = objectMapper.readTree(resp);
        assertThat(status.get("requires_renewal").asBoolean()).isFalse();
        assertThat(status.get("consents").isArray()).isTrue();
        assertThat(status.get("consents").size()).isEqualTo(4); // TERMS/PRIVACY/DISCLAIMER/MARKETING
    }

    @Test
    @DisplayName("재동의 기록 성공 — 204 No Content")
    void postConsent_recordsSuccessfully_returns204() throws Exception {
        String token = signupAndGetToken();

        ObjectNode body = objectMapper.createObjectNode()
                .put("terms_version",   "v1.0")
                .put("privacy_version", "v1.0")
                .put("marketing_opt_in", true);

        mockMvc.perform(post("/api/v1/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("재동의 후 getStatus — items에 최신 동의 내역 반영")
    void postConsent_thenGetStatus_reflectsLatest() throws Exception {
        String token = signupAndGetToken();

        // 마케팅 동의=true로 재동의
        ObjectNode body = objectMapper.createObjectNode()
                .put("terms_version",    "v1.0")
                .put("privacy_version",  "v1.0")
                .put("marketing_opt_in", true);

        mockMvc.perform(post("/api/v1/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());

        String resp = mockMvc.perform(get("/api/v1/consents/status")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode statusNode = objectMapper.readTree(resp);
        assertThat(statusNode.get("requires_renewal").asBoolean()).isFalse();

        // MARKETING 항목이 agreed=true 로 반영됐는지 확인
        JsonNode items = statusNode.get("consents");
        boolean marketingAgreed = false;
        for (JsonNode item : items) {
            if ("MARKETING".equals(item.get("consent_type").asText())) {
                marketingAgreed = item.get("agreed").asBoolean();
                break;
            }
        }
        assertThat(marketingAgreed).isTrue();
    }

    @Test
    @DisplayName("잘못된 버전 형식 (v1 대신 1.0) — 400 Bad Request")
    void postConsent_invalidVersionFormat_returns400() throws Exception {
        String token = signupAndGetToken();

        ObjectNode body = objectMapper.createObjectNode()
                .put("terms_version",   "1.0")  // v 없는 잘못된 형식
                .put("privacy_version", "v1.0");

        mockMvc.perform(post("/api/v1/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("terms_version 누락 — 400 Bad Request (@NotBlank)")
    void postConsent_missingTermsVersion_returns400() throws Exception {
        String token = signupAndGetToken();

        ObjectNode body = objectMapper.createObjectNode()
                .put("privacy_version", "v1.0");  // terms_version 누락

        mockMvc.perform(post("/api/v1/consents")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("인증 없이 동의 상태 조회 — 401 Unauthorized")
    void getStatus_noAuth_returns401() throws Exception {
        mockMvc.perform(get("/api/v1/consents/status"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("인증 없이 재동의 — 401 Unauthorized")
    void postConsent_noAuth_returns401() throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("terms_version",   "v1.0")
                .put("privacy_version", "v1.0");

        mockMvc.perform(post("/api/v1/consents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }
}
