package com.dartcommons.user;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] 휴대폰 OTP 인증 E2E 통합 테스트 — happy path·에러 케이스·rate limit·brute-force.
 * [이유] Testcontainers PostgreSQL 실 DB 검증. KakaoAlimtalkClient 모킹으로 실제 API 호출 차단.
 *       PhoneVerificationService는 Caffeine 인메모리 캐시 사용 — 별도 인프라 불필요.
 * [사이드 임팩트] 각 테스트는 uniqueEmail()로 독립 사용자 생성 — userId 충돌 없음.
 *               rate limit 캐시(minuteRateCache)는 동일 Spring context 내 공유.
 *               단, 각 테스트 사용자의 userId가 달라 간섭 없음.
 * [수정 시 고려사항] brute-force 차단 횟수(5회) 변경 시 bruteForce 테스트 수정 필요.
 *                  다중 인스턴스(Redis rate limit) 전환 후에는 통합 테스트 범위 확장 필요.
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
class PhoneVerifyIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;
    @MockitoBean KakaoAlimtalkClient       kakaoClient;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;

    private String uniqueEmail() {
        return "phone-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String signupAndGetToken() throws Exception {
        ObjectNode body = objectMapper.createObjectNode()
                .put("email",            uniqueEmail())
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

    private void sendOtp(String token) throws Exception {
        ObjectNode body = objectMapper.createObjectNode().put("phone_number", "01012345678");
        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("OTP 발송 성공 — 204 No Content")
    void sendOtp_success_returns204() throws Exception {
        String token = signupAndGetToken();
        sendOtp(token);
    }

    @Test
    @DisplayName("OTP 발송 + 검증 성공 — GET /me phone_verified=true")
    void confirmOtp_happyPath_phoneVerifiedTrue() throws Exception {
        String token = signupAndGetToken();

        ObjectNode sendBody = objectMapper.createObjectNode().put("phone_number", "01098765432");
        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sendBody.toString()))
                .andExpect(status().isNoContent());

        // 카카오 클라이언트로 전달된 OTP 코드 캡처
        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        verify(kakaoClient).sendOtp(anyString(), codeCaptor.capture());
        String capturedCode = codeCaptor.getValue();

        ObjectNode confirmBody = objectMapper.createObjectNode().put("code", capturedCode);
        mockMvc.perform(post("/api/v1/users/me/phone/verify/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(confirmBody.toString()))
                .andExpect(status().isNoContent());

        String meResp = mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode me = objectMapper.readTree(meResp);
        assertThat(me.get("phone_verified").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("잘못된 OTP 코드 — 400 INVALID_OTP")
    void confirmOtp_wrongCode_returns400() throws Exception {
        String token = signupAndGetToken();
        sendOtp(token);

        ObjectNode body = objectMapper.createObjectNode().put("code", "000000");
        mockMvc.perform(post("/api/v1/users/me/phone/verify/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("OTP 발송 없이 검증 — 410 OTP_EXPIRED (캐시 미존재)")
    void confirmOtp_noOtpInCache_returns410() throws Exception {
        String token = signupAndGetToken();
        // sendOtp 호출 없이 바로 confirm → 캐시에 엔트리 없음

        ObjectNode body = objectMapper.createObjectNode().put("code", "123456");
        mockMvc.perform(post("/api/v1/users/me/phone/verify/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isGone());
    }

    @Test
    @DisplayName("OTP 발송 rate limit (1분 1회) — 두 번째 요청 429")
    void sendOtp_secondRequestWithinMinute_returns429() throws Exception {
        String token = signupAndGetToken();

        ObjectNode body = objectMapper.createObjectNode().put("phone_number", "01011112222");

        // 첫 번째 발송 — 성공
        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isNoContent());

        // 두 번째 발송 — 1분 이내 재시도 → 429
        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("OTP 검증 5회 실패 후 6번째 시도 — 429 brute-force 차단")
    void confirmOtp_bruteForce6thAttempt_returns429() throws Exception {
        String token = signupAndGetToken();
        sendOtp(token);

        ObjectNode wrongBody = objectMapper.createObjectNode().put("code", "000000");

        // 시도 1~5: 400 INVALID_OTP
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/users/me/phone/verify/confirm")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(wrongBody.toString()))
                    .andExpect(status().isBadRequest());
        }

        // 6번째: 429 RATE_LIMIT_EXCEEDED (캐시 무효화됨)
        mockMvc.perform(post("/api/v1/users/me/phone/verify/confirm")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(wrongBody.toString()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    @DisplayName("인증 없이 OTP 발송 — 401 Unauthorized")
    void sendOtp_noAuth_returns401() throws Exception {
        ObjectNode body = objectMapper.createObjectNode().put("phone_number", "01012345678");
        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("전화번호 형식 오류 — 010 아닌 번호 400 Bad Request")
    void sendOtp_invalidPhoneFormat_returns400() throws Exception {
        String token = signupAndGetToken();
        ObjectNode body = objectMapper.createObjectNode().put("phone_number", "01112345678"); // 011

        mockMvc.perform(post("/api/v1/users/me/phone/verify")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isBadRequest());
    }
}
