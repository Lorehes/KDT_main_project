package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.entities.BackfillJob;
import com.dartcommons.disclosure.repositories.BackfillJobRepository;
import com.dartcommons.disclosure.services.BackfillJobService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * [목적] 비동기 백필 잡 컨트롤러 — 인증/잡 생성(202)/상태 조회 검증.
 * [이유] /admin/** Security 가드 + 잡 생성/조회 REST 계약 검증.
 * [사이드 임팩트] BackfillJobService.runAsync는 mock — 실제 @Async 트리거 자체는 단위 테스트 BackfillJobServiceTest에서 검증.
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
class BackfillJobControllerTest {

    @MockitoBean DisclosurePollingJob pollingJob;

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired BackfillJobRepository jobRepository;
    @Autowired BackfillJobService jobService;

    @BeforeEach
    void cleanup() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("POST /admin/disclosures/backfill/jobs — 인증 없이 401")
    void createJob_withoutAuth_returns401() throws Exception {
        mockMvc.perform(post("/admin/disclosures/backfill/jobs")
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-30"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /admin/disclosures/backfill/jobs — 인증 후 202 + jobId 반환")
    void createJob_authorized_returns202() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/disclosures/backfill/jobs")
                        .with(httpBasic("admin", "test-admin-password"))
                        .param("from", "2024-01-01")
                        .param("to", "2024-01-30"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.jobId").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        UUID jobId = UUID.fromString(body.get("jobId").asText());
        assertThat(jobRepository.findByJobId(jobId)).isPresent();
    }

    @Test
    @DisplayName("GET /admin/disclosures/backfill/jobs/{id} — 잡 상태 조회")
    void getJob_returnsStatus() throws Exception {
        BackfillJob job = jobService.createJob(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), false);

        mockMvc.perform(get("/admin/disclosures/backfill/jobs/" + job.getJobId())
                        .with(httpBasic("admin", "test-admin-password")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(job.getJobId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    @DisplayName("GET /admin/disclosures/backfill/jobs/{id} — 존재 안 함 404")
    void getJob_notFound_returns404() throws Exception {
        mockMvc.perform(get("/admin/disclosures/backfill/jobs/" + UUID.randomUUID())
                        .with(httpBasic("admin", "test-admin-password")))
                .andExpect(status().isNotFound());
    }
}
