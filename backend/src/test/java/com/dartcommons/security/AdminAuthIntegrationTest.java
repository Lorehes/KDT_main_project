package com.dartcommons.security;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.disclosure.services.DisclosureBackfillService.BackfillResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] /admin/** 경로의 Spring Security HTTP Basic 가드 검증.
 * [이유] BackfillController는 DART 65k+ 호출 트리거 가능 — 인증 없이 접근 불가해야 함.
 *       deferred 보안 게이트(통합기획서 §11) 충족 검증.
 * [사이드 임팩트] 백필 서비스 자체는 mock 처리 — 인증 레이어만 검증.
 * [수정 시 고려사항] JWT 도입 시 @WithJwt 또는 토큰 헤더 검증으로 교체.
 *                  401(미인증)/403(권한 없음) 구분 검증.
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
        "dartcommons.admin.password=test-admin-password"
})
class AdminAuthIntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;

    @Autowired MockMvc mockMvc;

    @Test
    @DisplayName("/admin/disclosures/backfill — 인증 없이 호출 시 401 Unauthorized")
    void backfill_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/admin/disclosures/backfill")
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/admin/disclosures/backfill — 잘못된 자격증명으로 401")
    void backfill_wrongCredentials_returns401() throws Exception {
        mockMvc.perform(post("/admin/disclosures/backfill")
                        .with(httpBasic("admin", "wrong-password"))
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("/admin/disclosures/backfill — 올바른 admin 자격증명으로 200")
    void backfill_validCredentials_returns200() throws Exception {
        when(backfillService.backfill(any(), any(), anyBoolean()))
                .thenReturn(new BackfillResult(
                        LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), 1, 0, 0));

        mockMvc.perform(post("/admin/disclosures/backfill")
                        .with(httpBasic("admin", "test-admin-password"))
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-30"))
                .andExpect(status().isOk());
    }
}
