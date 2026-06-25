package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
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

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] DisclosureQueryService FREE/PRO 티어 날짜 정책 + size 클램핑 통합 테스트.
 *       FREE 날짜 강제(오늘만), FREE size 클램핑(≤5) + total_elements 정확성, PRO 과거 날짜 통과를 검증.
 * [이유] 티어별 공시 접근 정책(dashboard-real-data R3)은 비즈니스 핵심 규칙 — Testcontainers PostgreSQL로
 *       실 DB 검증(Mock DB 금지, CLAUDE.md §6-6). 날짜/size 오버라이드는 서비스 레이어에서 발생하므로
 *       HTTP 계층 E2E 테스트가 유일한 통합 검증 경로.
 * [사이드 임팩트] 각 테스트는 고유 이메일 + UUID rcept_no로 데이터 간섭 없음.
 *               V10 seed 종목(005930)을 FK 참조 — 해당 스탁코드가 stocks 테이블에 존재해야 함.
 *               FREE 티어 size 클램핑 테스트는 공시 6건을 삽입하므로 다른 테스트와 날짜 충돌 없음
 *               (고유 이메일 포트폴리오로 격리).
 * [수정 시 고려사항] FREE 티어 일 최대 건수 정책(현재 5) 변경 시 size 클램핑 어서션도 함께 수정.
 *                  PRO 날짜 통과 테스트는 과거 고정 날짜("2025-01-15") 사용 — 해당 날짜 공시가
 *                  다른 테스트와 겹칠 수 있으나 포트폴리오 격리로 안전.
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
class DisclosureQueryServiceIntegrationTest {

    @MockitoBean DisclosurePollingJob      pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;
    @MockitoBean EmailVerificationService  emailVerificationService;

    @Autowired MockMvc      mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void bypassEmailVerification() {
        when(emailVerificationService.isEmailVerified(anyString())).thenReturn(true);
    }

    private String uniqueEmail() {
        return "dqs-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10) + "@test.com";
    }

    private String uniqueRceptNo() {
        return "2025" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private long insertDisclosure(String rceptNo, String stockCode, String rceptDt) {
        Long id = jdbc.queryForObject(
                "INSERT INTO disclosures (rcept_no, corp_code, stock_code, corp_name, report_nm, rcept_dt, disclosure_type, collected_at) " +
                "VALUES (?, '00000001', ?, '테스트회사', '테스트공시', ?::date, 'A001', now()) RETURNING id",
                Long.class, rceptNo, stockCode, rceptDt);
        return Objects.requireNonNull(id);
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
        ObjectNode body = objectMapper.createObjectNode()
                .put("stock_code", stockCode);
        mockMvc.perform(post("/api/v1/portfolios")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString()))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("FREE 티어 날짜 강제 — 어제 공시 삽입 후 from=yesterday 파라미터 전달해도 어제 공시는 응답에 없음")
    void freeTier_dateForced_yesterdayDisclosureExcluded() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");

        // 어제 날짜 공시 삽입 — 고유 rcept_no로 식별
        String yesterday = LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString();
        String yesterdayRceptNo = uniqueRceptNo();
        insertDisclosure(yesterdayRceptNo, "005930", yesterday);

        // from=yesterday, to=yesterday 파라미터를 전달해도 FREE 티어는 오늘로 오버라이드됨
        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("from", yesterday)
                        .param("to",   yesterday))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(resp);
        JsonNode content = page.get("content");

        // FREE 티어: 어제 공시는 오늘 날짜 강제로 인해 응답에 포함되지 않음
        String todayBasic = LocalDate.now(ZoneId.of("Asia/Seoul")).toString().replace("-", "");
        String yesterdayBasic = yesterday.replace("-", "");
        for (JsonNode item : content) {
            String rceptDt = item.get("rcept_dt").asText(); // YYYYMMDD 포맷
            // content 내 모든 항목은 오늘 날짜여야 함 — 어제 날짜 절대 포함 불가
            assertThat(rceptDt).isNotEqualTo(yesterdayBasic)
                    .withFailMessage("FREE 티어 날짜 강제 실패: 어제(%s) 공시가 응답에 포함됨", yesterdayBasic);
            assertThat(rceptDt).isEqualTo(todayBasic);
        }
        // 어제 삽입한 공시(yesterdayRceptNo)가 rcept_no 기준으로 응답에 없어야 함
        boolean hasYesterdayDisclosure = false;
        for (JsonNode item : content) {
            if (item.get("rcept_no").asText().equals(yesterdayRceptNo)) {
                hasYesterdayDisclosure = true;
            }
        }
        assertThat(hasYesterdayDisclosure)
                .as("어제 삽입한 공시(rcept_no=%s)가 FREE 티어 응답에 포함되면 안 됨", yesterdayRceptNo)
                .isFalse();
    }

    @Test
    @DisplayName("FREE 티어 size 클램핑 + total_elements — 오늘 공시 6건 삽입, size=100 요청 시 content≤5 + total_elements=6")
    void freeTier_sizeClamped_totalElementsReflectsActualCount() throws Exception {
        String email = uniqueEmail();
        String token = signupAndGetToken(email);
        addPortfolio(token, "005930");

        // 오늘 날짜로 공시 6건 삽입
        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        for (int i = 0; i < 6; i++) {
            insertDisclosure(uniqueRceptNo(), "005930", today);
        }

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(resp);
        JsonNode content = page.get("content");
        JsonNode pageMeta = page.get("page");

        // FREE 티어: size는 5로 클램핑됨
        assertThat(content.size()).isLessThanOrEqualTo(5);
        // total_elements는 클램핑 전 전체 카운트 (오늘 이 포트폴리오 공시 = 6건 이상)
        assertThat(pageMeta.get("total_elements").asLong()).isGreaterThanOrEqualTo(6);
    }

    @Test
    @DisplayName("PRO 티어 파라미터 통과 — 과거 날짜 from/to 파라미터 그대로 적용, 과거 공시 반환")
    void proTier_pastDateParams_returnsPastDisclosures() throws Exception {
        String email = uniqueEmail();
        signupAndGetToken(email);
        jdbc.update("UPDATE users SET tier = 'PRO' WHERE email = ?", email);
        String proToken = loginAndGetToken(email);

        addPortfolio(proToken, "005930");

        // 과거 날짜 공시 삽입
        String pastDate = "2025-01-15";
        String pastRceptNo = uniqueRceptNo();
        insertDisclosure(pastRceptNo, "005930", pastDate);

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + proToken)
                        .param("from", pastDate)
                        .param("to",   pastDate))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(resp).get("content");
        // PRO 티어: 파라미터 오버라이드 없음 — 과거 날짜 공시 반환됨
        assertThat(content.size()).isGreaterThanOrEqualTo(1);

        boolean hasPastDisclosure = false;
        for (JsonNode item : content) {
            if (item.get("rcept_no").asText().equals(pastRceptNo)) {
                hasPastDisclosure = true;
                // 날짜 포맷은 YYYYMMDD — "20250115"
                assertThat(item.get("rcept_dt").asText()).isEqualTo("20250115");
            }
        }
        assertThat(hasPastDisclosure).isTrue();
    }
}
