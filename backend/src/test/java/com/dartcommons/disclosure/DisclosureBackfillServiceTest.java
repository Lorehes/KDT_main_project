package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.dto.RawDisclosureItem;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.disclosure.services.DisclosureBackfillService.BackfillResult;
import com.dartcommons.infrastructure.dart.DartClient;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/*
 * [목적] DisclosureBackfillService의 청크 분할, saveAll 배치, 커버 필터, 멱등을 Testcontainers로 검증.
 * [이유] 대량 적재 경로의 핵심 로직 — 청크 트랜잭션과 N+1 회피가 정확히 작동해야 백필 운영 가능.
 *       DartClient는 mock 처리 — 외부 호출 없이 fetchList 응답을 픽스처로 제어.
 * [사이드 임팩트] DisclosurePollingJob도 함께 mock 처리(@Scheduled 자동 호출 차단).
 * [수정 시 고려사항] 윈도우 분할 검증은 fetchList 호출 횟수로 간접 확인.
 *                  실 환경에서는 90일 윈도우 × 12 + α = 3년치(12~13 청크) 호출이 예상치.
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
class DisclosureBackfillServiceTest {

    @MockitoBean DisclosurePollingJob pollingJob;
    @MockitoBean DartClient dartClient;

    @Autowired private DisclosureBackfillService backfillService;
    @Autowired private DisclosureRepository disclosureRepository;
    @Autowired private StockRepository stockRepository;

    @BeforeEach
    void setUp() {
        disclosureRepository.deleteAll();
        stockRepository.deleteAll();
        stockRepository.save(Stock.builder()
                .stockCode("005930").corpCode("00126380").corpName("삼성전자").market("KOSPI").build());
        stockRepository.save(Stock.builder()
                .stockCode("000660").corpCode("00164779").corpName("SK하이닉스").market("KOSPI").build());
    }

    @Test
    @DisplayName("90일 단위 청크 분할 — from~to 범위가 윈도우 다중 호출로 분리된다")
    void backfill_splitsIntoChunks() {
        // fetchList는 호출마다 빈 리스트 반환(저장 0건)
        when(dartClient.fetchList(any(), any())).thenReturn(List.of());

        LocalDate from = LocalDate.of(2023, 1, 1);
        LocalDate to = LocalDate.of(2026, 1, 1);  // 3년 1일 = 1096일 / 90 = 13청크

        BackfillResult result = backfillService.backfill(from, to, false);

        assertThat(result.chunks()).isBetween(12, 14);
        assertThat(result.from()).isEqualTo(from);
        assertThat(result.to()).isEqualTo(to);
    }

    @Test
    @DisplayName("커버 종목만 저장 + 미커버는 skip")
    void backfill_filtersCoverage() {
        List<RawDisclosureItem> items = List.of(
                item("20240101000001", "005930", "삼성전자", "유상증자결정"),
                item("20240101000002", "999999", "미커버", "합병")
        );
        when(dartClient.fetchList(any(), any())).thenReturn(items).thenReturn(List.of());

        BackfillResult result = backfillService.backfill(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), false);

        assertThat(result.saved()).isEqualTo(1);
        assertThat(disclosureRepository.findAll()).hasSize(1);
        assertThat(disclosureRepository.findAll().get(0).getStockCode()).isEqualTo("005930");
    }

    @Test
    @DisplayName("청크 크기(500) 초과 배치도 정확히 saveAll로 적재된다")
    void backfill_chunkedSaveAll() {
        // 600건 항목 — 청크 크기 500 초과 → 2회 flush
        List<RawDisclosureItem> items = new ArrayList<>();
        for (int i = 0; i < 600; i++) {
            items.add(item(String.format("2024010100%04d", i), "005930", "삼성전자", "유상증자결정"));
        }
        when(dartClient.fetchList(any(), any())).thenReturn(items).thenReturn(List.of());

        BackfillResult result = backfillService.backfill(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), false);

        assertThat(result.saved()).isEqualTo(600);
        assertThat(disclosureRepository.count()).isEqualTo(600);
    }

    @Test
    @DisplayName("재실행 시 멱등 — 이미 저장된 공시는 skip")
    void backfill_idempotent() {
        List<RawDisclosureItem> items = List.of(
                item("20240101000010", "005930", "삼성전자", "유상증자결정")
        );
        when(dartClient.fetchList(any(), any())).thenReturn(items).thenReturn(List.of());

        BackfillResult first = backfillService.backfill(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), false);
        BackfillResult second = backfillService.backfill(
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 30), false);

        assertThat(first.saved()).isEqualTo(1);
        assertThat(second.saved()).isEqualTo(0);
        assertThat(disclosureRepository.count()).isEqualTo(1);
    }

    private RawDisclosureItem item(String rceptNo, String stockCode, String corpName, String reportNm) {
        return new RawDisclosureItem(rceptNo, "00126380", stockCode, corpName, reportNm, "20240101");
    }
}
