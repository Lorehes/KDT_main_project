package com.dartcommons.stocks;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.infrastructure.krx.KrxClient;
import com.dartcommons.infrastructure.krx.KrxClient.StockCloseInfo;
import com.dartcommons.stocks.entities.PriceBackfillJob;
import com.dartcommons.stocks.repositories.PriceBackfillJobRepository;
import com.dartcommons.stocks.services.PriceBackfillService;
import com.dartcommons.user.services.EmailVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/*
 * [목적] PriceBackfillService(krx-price-timeseries Wave B) 검증 — V28 마이그레이션, LocalDate 커서 영속화,
 *       async doBackfill의 stock_prices 적재·FK 필터·안전망(조기 중단).
 * [이유] Mock DB 금지(CLAUDE.md §6-6): price_backfill_jobs 컬럼·last_processed_date DATE 커서를 실 DB로 검증.
 *       KrxClient는 @MockitoBean으로 외부 KRX 호출 차단(결정론적 응답 주입).
 * [사이드 임팩트] 공유 stock_prices·price_backfill_jobs에 데이터 삽입 — @BeforeEach 정리.
 * [수정 시 고려사항] doBackfill은 3년 평일 역순 반복 — mock이 데이터를 주면 ~780행 적재. Awaitility로 완료 대기.
 */
@SpringBootTest
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
class PriceBackfillJobIT {

    @MockitoBean KrxClient                krxClient;
    @MockitoBean DisclosurePollingJob     pollingJob;
    @MockitoBean DisclosureBackfillService backfillService;
    @MockitoBean EmailVerificationService emailVerificationService;

    @Autowired PriceBackfillService        priceBackfillService;
    @Autowired PriceBackfillJobRepository  jobRepository;
    @Autowired JdbcTemplate                jdbcTemplate;

    @BeforeEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM stock_prices");
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("createJob → PENDING 저장(V28 마이그레이션 적용 검증)")
    void createJob_returnsPending() {
        PriceBackfillJob job = priceBackfillService.createJob();
        assertThat(job.getJobId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(PriceBackfillJob.Status.PENDING);
        assertThat(job.getProcessed()).isZero();
    }

    @Test
    @DisplayName("doBackfill 정상 — KRX stub(날짜 echo) → stock_prices 적재 + 잡 SUCCEEDED + last_processed_date 커서")
    void doBackfill_happyPath_insertsRowsAndSucceeds() {
        // 커버 종목 005930(삼성전자, 시드)만 각 거래일 종가 반환 — priceAsof=조회 날짜 echo
        given(krxClient.fetchClosePricesForDate(any())).willAnswer(inv -> {
            LocalDate d = inv.getArgument(0);
            return Map.of("005930", new StockCloseInfo(new BigDecimal("70000"), d));
        });

        Optional<PriceBackfillJob> started = priceBackfillService.createAndStartAsync();
        assertThat(started).isPresent();
        UUID jobId = started.get().getJobId();

        await().atMost(Duration.ofSeconds(60)).until(() ->
                priceBackfillService.findByJobId(jobId)
                        .map(j -> j.getStatus() == PriceBackfillJob.Status.SUCCEEDED)
                        .orElse(false));

        Long rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stock_prices WHERE stock_code = '005930'", Long.class);
        assertThat(rows).isGreaterThan(0L);   // 3년 평일 수만큼(비거래일 무관하게 mock이 데이터 반환)

        PriceBackfillJob done = jobRepository.findByJobId(jobId).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(PriceBackfillJob.Status.SUCCEEDED);
        assertThat(done.getLastProcessedDate()).isNotNull();   // LocalDate 커서 영속 검증
        assertThat(done.getProcessed()).isGreaterThan(0);
    }

    @Test
    @DisplayName("doBackfill 안전망 — KRX 전건 빈 응답 → 20 평일 후 조기 중단 → 잡 FAILED")
    void doBackfill_allEmpty_earlyAbortFails() {
        given(krxClient.fetchClosePricesForDate(any())).willReturn(Map.of());

        Optional<PriceBackfillJob> started = priceBackfillService.createAndStartAsync();
        assertThat(started).isPresent();
        UUID jobId = started.get().getJobId();

        await().atMost(Duration.ofSeconds(30)).until(() ->
                priceBackfillService.findByJobId(jobId)
                        .map(j -> j.getStatus() == PriceBackfillJob.Status.FAILED)
                        .orElse(false));

        PriceBackfillJob failed = jobRepository.findByJobId(jobId).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(PriceBackfillJob.Status.FAILED);
        assertThat(failed.getErrorMessage()).contains("조기 중단");

        Long rows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM stock_prices", Long.class);
        assertThat(rows).isZero();
    }

    @Test
    @DisplayName("중복 시작 — 실행 중 createAndStartAsync 재호출 시 빈 Optional(409 근거)")
    void createAndStartAsync_whenRunning_returnsEmpty() {
        // 느린 stub으로 첫 잡을 실행 상태로 유지 — 각 날짜 호출에 소폭 지연
        given(krxClient.fetchClosePricesForDate(any())).willAnswer(inv -> {
            Thread.sleep(5);
            LocalDate d = inv.getArgument(0);
            return Map.of("005930", new StockCloseInfo(new BigDecimal("70000"), d));
        });

        Optional<PriceBackfillJob> first = priceBackfillService.createAndStartAsync();
        assertThat(first).isPresent();

        // 첫 잡이 도는 동안 두 번째 시도 → CAS 실패로 빈 Optional
        Optional<PriceBackfillJob> second = priceBackfillService.createAndStartAsync();
        assertThat(second).isEmpty();

        // 정리: 첫 잡 완료 대기
        await().atMost(Duration.ofSeconds(60)).until(() -> !priceBackfillService.isRunning());
    }
}
