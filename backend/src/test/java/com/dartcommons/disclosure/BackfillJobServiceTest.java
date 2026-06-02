package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.entities.BackfillJob;
import com.dartcommons.disclosure.repositories.BackfillJobRepository;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.disclosure.services.BackfillJobService;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.infrastructure.dart.DartClient;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/*
 * [목적] BackfillJobService — 비동기 잡 생성·실행·진행률 갱신·완료 동작을 Testcontainers로 검증.
 * [이유] @Async 동작은 단위 테스트로 충분치 않음 — 실 DB + Spring 컨텍스트에서 검증.
 * [사이드 임팩트] DartClient는 mock. 잡 완료까지 Awaitility 폴링 — 짧은 timeout으로 빠른 실패.
 * [수정 시 고려사항] 비동기 타이밍 의존 — 환경에 따라 timeout 조정 필요.
 *                  실패 시나리오는 DartClient mock에 throw 주입.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password"
})
class BackfillJobServiceTest {

    @MockitoBean DisclosurePollingJob pollingJob;
    @MockitoBean DartClient dartClient;

    @Autowired BackfillJobService jobService;
    @Autowired BackfillJobRepository jobRepository;
    @Autowired DisclosureRepository disclosureRepository;
    @Autowired StockRepository stockRepository;

    @BeforeEach
    void setUp() {
        disclosureRepository.deleteAll();
        jobRepository.deleteAll();
        stockRepository.deleteAll();
        stockRepository.save(Stock.builder()
                .stockCode("005930").corpCode("00126380").corpName("삼성전자").market("KOSPI").build());
    }

    @Test
    @DisplayName("createJob — PENDING 상태로 잡 생성")
    void createJob_initialStateIsPending() {
        BackfillJob job = jobService.createJob(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), false);

        assertThat(job.getStatus()).isEqualTo(BackfillJob.Status.PENDING);
        assertThat(job.getJobId()).isNotNull();
        assertThat(job.getChunksDone()).isZero();
        assertThat(job.getStartedAt()).isNull();
    }

    @Test
    @DisplayName("runAsync — 잡이 SUCCEEDED로 전이, chunksDone 갱신")
    void runAsync_completesSuccessfully() {
        when(dartClient.fetchList(any(), any())).thenReturn(List.of());
        BackfillJob job = jobService.createJob(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 3, 31), false);  // 3개월 ≈ 1~2 청크

        jobService.runAsync(job.getJobId());

        UUID jobId = job.getJobId();
        Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            BackfillJob updated = jobRepository.findByJobId(jobId).orElseThrow();
            assertThat(updated.getStatus()).isEqualTo(BackfillJob.Status.SUCCEEDED);
            assertThat(updated.getChunksDone()).isGreaterThanOrEqualTo(1);
            assertThat(updated.getFinishedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("calculateChunks — 3년치는 13청크(90일 윈도우)")
    void calculateChunks_threeYears() {
        int chunks = DisclosureBackfillService.calculateChunks(
                LocalDate.of(2023, 6, 1), LocalDate.of(2026, 6, 1));
        assertThat(chunks).isEqualTo(13);
    }
}
