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
 *       FREE 5일 경계 클램프(창 내 포함·창 밖 제외·from>to 역전), FREE size 클램핑(≤5) + total_elements 정확성,
 *       PRO 과거 날짜 통과를 검증.
 * [이유] 티어별 공시 접근 정책(portfolios-recent-disclosures-5d — dashboard-real-data R3 완화)은
 *       비즈니스 핵심 규칙 — Testcontainers PostgreSQL로 실 DB 검증(Mock DB 금지, CLAUDE.md §6-6).
 *       날짜/size 오버라이드는 서비스 레이어에서 발생하므로 HTTP 계층 E2E 테스트가 유일한 통합 검증 경로.
 * [사이드 임팩트] 각 테스트는 고유 이메일 + UUID rcept_no로 데이터 간섭 없음.
 *               V10 seed 종목(005930)을 FK 참조 — 해당 스탁코드가 stocks 테이블에 존재해야 함.
 *               FREE 티어 size 클램핑 테스트는 공시 6건을 삽입하므로 다른 테스트와 날짜 충돌 없음
 *               (고유 이메일 포트폴리오로 격리).
 * [수정 시 고려사항] FREE 티어 일 최대 건수 정책(현재 5) 변경 시 size 클램핑 어서션도 함께 수정.
 *                  FREE 날짜 창(FREE_WINDOW_DAYS, 현재 5일) 변경 시 창 경계 테스트의 minusDays 값도 동기화.
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
        return insertDisclosure(rceptNo, stockCode, rceptDt, "테스트회사", "테스트공시");
    }

    private long insertDisclosure(String rceptNo, String stockCode, String rceptDt, String corpName, String reportNm) {
        Long id = jdbc.queryForObject(
                "INSERT INTO disclosures (rcept_no, corp_code, stock_code, corp_name, report_nm, rcept_dt, disclosure_type, collected_at) " +
                "VALUES (?, '00000001', ?, ?, ?, ?::date, 'A001', now()) RETURNING id",
                Long.class, rceptNo, stockCode, corpName, reportNm, rceptDt);
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
    @DisplayName("FREE 티어 5일 경계 클램프 — 창 내(어제) 공시 포함, 창 밖(6일 전) 공시 제외")
    void freeTier_fiveDayWindowClamp_insideIncluded_outsideExcluded() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        // 066570: 본 클래스 전용 종목 — 005930은 타 테스트가 오늘 공시를 다수 삽입해
        // FREE page=0·size≤5 고정 + 최신순 정렬에서 어제 공시가 첫 페이지 밖으로 밀림(격리 목적)
        addPortfolio(token, "066570");

        LocalDate seoulToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 정확한 경계 픽스처로 off-by-one 고정 (5일 창 = [today-4, today]):
        //   창 내부점: 어제 / 창 시작 경계(포함): today-4 / 창 밖 첫 날(제외): today-5 / 창 밖 내부점: today-6
        String yesterday   = seoulToday.minusDays(1).toString();
        String windowStart = seoulToday.minusDays(4).toString();
        String justOutside = seoulToday.minusDays(5).toString();
        String sixDaysAgo  = seoulToday.minusDays(6).toString();
        String insideRceptNo      = uniqueRceptNo();
        String boundaryRceptNo    = uniqueRceptNo();
        String justOutsideRceptNo = uniqueRceptNo();
        String outsideRceptNo     = uniqueRceptNo();
        insertDisclosure(insideRceptNo,      "066570", yesterday);
        insertDisclosure(boundaryRceptNo,    "066570", windowStart);
        insertDisclosure(justOutsideRceptNo, "066570", justOutside);
        insertDisclosure(outsideRceptNo,     "066570", sixDaysAgo);

        // from=6일 전을 요청해도 FREE는 창 시작(today-4)으로 당겨짐 — 창 내 2건 통과, 창 밖 2건 제외
        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("from", sixDaysAgo)
                        .param("to",   seoulToday.toString())
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(resp).get("content");

        String windowStartBasic = windowStart.replace("-", "");
        boolean hasInside = false;
        boolean hasBoundary = false;
        for (JsonNode item : content) {
            String rceptDt = item.get("rcept_dt").asText(); // YYYYMMDD 포맷
            // content 내 모든 항목은 5일 창 안이어야 함 — 창 밖(today-5 이하) 절대 포함 불가
            assertThat(rceptDt.compareTo(windowStartBasic))
                    .withFailMessage("FREE 5일 클램프 실패: 창 밖(%s < %s) 공시가 응답에 포함됨", rceptDt, windowStartBasic)
                    .isGreaterThanOrEqualTo(0);
            String rceptNo = item.get("rcept_no").asText();
            if (rceptNo.equals(insideRceptNo))   hasInside = true;
            if (rceptNo.equals(boundaryRceptNo)) hasBoundary = true;
            assertThat(rceptNo)
                    .as("창 밖 공시(today-5=%s, today-6=%s)가 FREE 응답에 포함되면 안 됨", justOutsideRceptNo, outsideRceptNo)
                    .isNotIn(justOutsideRceptNo, outsideRceptNo);
        }
        // 어제(창 내부점) — 기존 "오늘 강제" 시절에는 제외되던 케이스
        assertThat(hasInside)
                .as("어제 공시(rcept_no=%s)는 5일 창 내이므로 FREE 응답에 포함되어야 함", insideRceptNo)
                .isTrue();
        // today-4(창 시작 경계, 포함) — FREE_WINDOW_DAYS·minusDays 펜스포스트 회귀를 정확히 고정
        assertThat(hasBoundary)
                .as("창 시작 경계 공시(today-4, rcept_no=%s)는 포함되어야 함 — off-by-one 회귀 감지", boundaryRceptNo)
                .isTrue();
    }

    @Test
    @DisplayName("FREE 티어 클램프 후 from>to 역전 — 미래 from 요청 시 빈 결과 (오류 아님)")
    void freeTier_clampedRangeInverted_returnsEmpty() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "066570"); // 격리용 전용 종목 (위 테스트 주석 참조)

        // 오늘 공시가 존재해도 from=내일이면 [내일, 오늘] 역전 범위 → 0건
        LocalDate seoulToday = LocalDate.now(ZoneId.of("Asia/Seoul"));
        insertDisclosure(uniqueRceptNo(), "066570", seoulToday.toString());

        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("from", seoulToday.plusDays(1).toString()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode page = objectMapper.readTree(resp);
        assertThat(page.get("content").size()).isZero();
        assertThat(page.get("page").get("total_elements").asLong()).isZero();
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

    @Test
    @DisplayName("q 파라미터 — corp_name 포함 키워드 일치 공시 반환, 불일치 키워드는 해당 공시 미포함")
    void qSearch_matchesCorpName_returnsFilteredDisclosures() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");

        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        // 고유 키워드를 corp_name에 포함한 공시 삽입 — 다른 테스트 데이터와 충돌 방지
        String uniqueKeyword = "UniSrch" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        String matchRceptNo = uniqueRceptNo();
        insertDisclosure(matchRceptNo, "005930", today, uniqueKeyword + "Corp", "정기공시");

        // q 일치 — uniqueKeyword가 corp_name에 포함된 공시 반환됨
        String respMatch = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("q", uniqueKeyword))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode contentMatch = objectMapper.readTree(respMatch).get("content");
        assertThat(contentMatch.size()).isGreaterThanOrEqualTo(1);
        boolean hasMatch = false;
        for (JsonNode item : contentMatch) {
            if (item.get("rcept_no").asText().equals(matchRceptNo)) hasMatch = true;
        }
        assertThat(hasMatch).as("q=uniqueKeyword 검색 결과에 삽입한 공시가 포함되어야 함").isTrue();

        // q 불일치 — ZZZ_NO_MATCH로는 위 공시가 응답에 없어야 함
        String respNoMatch = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("q", "ZZZ_NO_MATCH_XYZ"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode contentNoMatch = objectMapper.readTree(respNoMatch).get("content");
        for (JsonNode item : contentNoMatch) {
            assertThat(item.get("rcept_no").asText())
                    .as("q=ZZZ_NO_MATCH 결과에 uniqueKeyword 공시가 포함되면 안 됨")
                    .isNotEqualTo(matchRceptNo);
        }
    }

    @Test
    @DisplayName("q 빈 문자열 — null과 동일하게 처리, 기존 동작(필터 없음) 보존")
    void qEmpty_treatedAsNull_returnsAllDisclosures() throws Exception {
        String token = signupAndGetToken(uniqueEmail());
        addPortfolio(token, "005930");

        String today = LocalDate.now(ZoneId.of("Asia/Seoul")).toString();
        String rceptNo = uniqueRceptNo();
        insertDisclosure(rceptNo, "005930", today, "빈큐테스트회사", "빈큐테스트공시");

        // q="" (빈 문자열) → 필터 없음 → 삽입한 공시 반환됨
        String resp = mockMvc.perform(get("/api/v1/disclosures")
                        .header("Authorization", "Bearer " + token)
                        .param("q", ""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode content = objectMapper.readTree(resp).get("content");
        boolean found = false;
        for (JsonNode item : content) {
            if (item.get("rcept_no").asText().equals(rceptNo)) found = true;
        }
        assertThat(found).as("q='' 시 삽입한 공시가 응답에 포함되어야 함(필터 없음)").isTrue();
    }
}
