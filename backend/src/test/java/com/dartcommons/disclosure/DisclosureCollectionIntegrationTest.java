package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.dto.RawDisclosureItem;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.disclosure.services.DisclosureCollectionService;
import com.dartcommons.shared.event.DisclosureCollectedEvent;
import com.dartcommons.stocks.entities.Stock;
import com.dartcommons.stocks.repositories.StockRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] DisclosureCollectionService의 멱등·커버필터·이벤트 발행을 Testcontainers 실 PostgreSQL로 검증.
 * [이유] Mock DB 금지(CLAUDE.md §6-6) — 실제 UNIQUE 제약, FK, 인덱스 동작을 검증.
 *       DartClient는 테스트 대상이 아니라 DartListResponse.Item 픽스처로 직접 주입.
 *       stocks-master-seed Spec 카드 #10 회귀로 픽스처가 JdbcTemplate INSERT → StockRepository.save로 전환됨.
 * [사이드 임팩트] Stock 엔티티가 V2 스키마와 정합하지 않으면 save 시점에 부팅/매핑 오류로 노출 — ddl-auto: validate 보조 검증.
 * [수정 시 고려사항] V10 시드 마이그레이션 도입 후에도 본 테스트는 fixture 격리 유지(테스트는 deleteAll 후 재적재).
 *                  DART API 실제 호출 테스트는 별도 @Tag("dart-live") 테스트로 분리 권장.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, DisclosureCollectionIntegrationTest.TestConfig.class})
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost"
})
class DisclosureCollectionIntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob; // 실제 DART API 호출 방지

    @Autowired private DisclosureCollectionService collectionService;
    @Autowired private DisclosureRepository disclosureRepository;
    @Autowired private StockRepository stockRepository;
    @Autowired private TestEventCaptor eventCaptor;

    @BeforeEach
    void setUp() {
        disclosureRepository.deleteAll();
        stockRepository.deleteAll();
        eventCaptor.clear();

        // stocks 픽스처 — 커버 종목 필터 통과를 위해 필요 (엔티티 직접 저장)
        stockRepository.save(Stock.builder()
                .stockCode("005930")
                .corpCode("00126380")
                .corpName("삼성전자")
                .market("KOSPI")
                .build());
    }

    @Test
    @DisplayName("커버 종목 공시 1건이 정상 저장되고 이벤트가 발행된다")
    @Transactional
    void collect_coveredStock_savedAndEventPublished() {
        List<RawDisclosureItem> items = List.of(item("20260601000001", "005930", "삼성전자", "유상증자결정"));

        int saved = collectionService.collect(items);

        assertThat(saved).isEqualTo(1);
        assertThat(disclosureRepository.findAll()).hasSize(1);
        Disclosure disclosure = disclosureRepository.findAll().get(0);
        assertThat(disclosure.getRceptNo()).isEqualTo("20260601000001");
        assertThat(disclosure.getDisclosureType()).isEqualTo("RIGHTS_OFFERING");
        assertThat(disclosure.getCorpName()).isEqualTo("삼성전자"); // DART 원본 그대로
        assertThat(eventCaptor.events()).hasSize(1);
    }

    @Test
    @DisplayName("동일 rcept_no는 두 번째 호출에서 skip된다 (멱등)")
    void collect_duplicateRceptNo_skipped() {
        List<RawDisclosureItem> items = List.of(item("20260601000002", "005930", "삼성전자", "사업보고서"));

        collectionService.collect(items);
        int secondSaved = collectionService.collect(items);

        assertThat(secondSaved).isEqualTo(0);
        assertThat(disclosureRepository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("미커버 종목(stocks 없음)은 skip된다")
    void collect_uncoveredStock_skipped() {
        List<RawDisclosureItem> items = List.of(item("20260601000003", "999999", "미등록회사", "단일판매ㆍ공급계약체결"));

        int saved = collectionService.collect(items);

        assertThat(saved).isEqualTo(0);
        assertThat(disclosureRepository.findAll()).isEmpty();
        assertThat(eventCaptor.events()).isEmpty();
    }

    @Test
    @DisplayName("비상장(stock_code 공백)은 커버필터에서 skip된다")
    void collect_blankStockCode_skipped() {
        List<RawDisclosureItem> items = List.of(item("20260601000004", null, "비상장법인", "반기보고서"));

        int saved = collectionService.collect(items);

        assertThat(saved).isEqualTo(0);
    }

    @Test
    @DisplayName("분류 미매칭 공시는 OTHER 타입으로 저장된다")
    @Transactional
    void collect_unknownReportNm_savedAsOther() {
        List<RawDisclosureItem> items = List.of(item("20260601000005", "005930", "삼성전자", "알수없는공시유형"));

        collectionService.collect(items);

        Disclosure disclosure = disclosureRepository.findAll().get(0);
        assertThat(disclosure.getDisclosureType()).isEqualTo("OTHER");
    }

    @Test
    @DisplayName("혼합 배치(커버/미커버/중복)에서 커버만 저장된다")
    @Transactional
    void collect_mixedBatch_onlyCoveredSaved() {
        List<RawDisclosureItem> items = List.of(
                item("20260601000010", "005930", "삼성전자", "유상증자결정"),  // 커버, 신규
                item("20260601000011", "999999", "미커버회사", "합병"),         // 미커버
                item("20260601000010", "005930", "삼성전자", "유상증자결정")    // 중복
        );

        int saved = collectionService.collect(items);

        assertThat(saved).isEqualTo(1);
        assertThat(disclosureRepository.findAll()).hasSize(1);
    }

    // ---- 픽스처 헬퍼 ----

    private RawDisclosureItem item(String rceptNo, String stockCode, String corpName, String reportNm) {
        return new RawDisclosureItem(
                rceptNo,
                "00126380",        // corpCode (삼성전자)
                stockCode,         // 도메인 DTO는 정규화된 null/값
                corpName,
                reportNm,
                "20260601"         // rceptDt
        );
    }

    // ---- 이벤트 캡처 (TestConfiguration으로 등록) ----

    @TestConfiguration
    static class TestConfig {
        @Bean
        TestEventCaptor testEventCaptor() {
            return new TestEventCaptor();
        }
    }

    static class TestEventCaptor {
        private final List<DisclosureCollectedEvent> captured = new ArrayList<>();

        @EventListener
        void on(DisclosureCollectedEvent event) {
            captured.add(event);
        }

        List<DisclosureCollectedEvent> events() {
            return List.copyOf(captured);
        }

        void clear() {
            captured.clear();
        }
    }
}
