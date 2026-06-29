package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.disclosure.services.DisclosureContentService;
import com.dartcommons.infrastructure.dart.DartApiException;
import com.dartcommons.infrastructure.dart.DartDocumentClient;
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
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/*
 * [목적] DisclosureContentService.fetchAndSave()의 DB 반영(content_text, content_fetched_at) 검증.
 *       실제 PostgreSQL(Testcontainers)에서 V24 마이그레이션과 엔티티 매핑이 올바른지 확인.
 * [이유] Mock DB 금지(CLAUDE.md §6-6) — content_fetched_at 컬럼이 실제 존재하는지, 인덱스가 정상 적용되는지 검증.
 *       DartDocumentClient는 @MockitoBean으로 대체 — 외부 DART API 호출 없이 서비스 로직만 검증.
 * [사이드 임팩트] V24 마이그레이션이 누락되면 Flyway 부팅 시 오류 → 테스트 자체가 실패해 회귀 방어.
 * [수정 시 고려사항] DartDocumentClient @Retryable은 테스트에서도 활성화됨 — RestClientException 테스트 시
 *                  실제 3회 재시도가 발생. 테스트에서는 재시도 제외(@SpringBootTest 기본).
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
class DisclosureContentServiceIT {

    @MockitoBean
    private DartDocumentClient dartDocumentClient;

    @MockitoBean
    private DisclosurePollingJob pollingJob;

    @Autowired private DisclosureContentService disclosureContentService;
    @Autowired private DisclosureRepository disclosureRepository;
    @Autowired private StockRepository stockRepository;

    private Disclosure testDisclosure;

    @BeforeEach
    void setUp() {
        disclosureRepository.deleteAll();
        stockRepository.deleteAll();

        stockRepository.save(Stock.builder()
                .stockCode("005930")
                .corpCode("00126380")
                .corpName("삼성전자")
                .market("KOSPI")
                .build());

        testDisclosure = disclosureRepository.save(Disclosure.builder()
                .rceptNo("20260601000001")
                .corpCode("00126380")
                .stockCode("005930")
                .corpName("삼성전자")
                .reportNm("유상증자결정")
                .rceptDt(LocalDate.of(2026, 6, 1))
                .disclosureType("RIGHTS_OFFERING")
                .collectedAt(OffsetDateTime.now())
                .build());
    }

    @Test
    @DisplayName("성공 경로 — content_text와 content_fetched_at이 DB에 갱신된다")
    void fetchAndSave_success_updatesContentTextAndFetchedAt() {
        byte[] fakeHtml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><p>유상증자 본문</p></body></html>"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(dartDocumentClient.fetchDocumentBytes(anyString())).thenReturn(fakeHtml);

        disclosureContentService.fetchAndSave(testDisclosure.getId());

        Disclosure updated = disclosureRepository.findById(testDisclosure.getId()).orElseThrow();
        assertThat(updated.getContentText()).contains("유상증자 본문");
        assertThat(updated.getContentFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("DartApiException(영구 실패) — content_fetched_at은 기록되고 content_text는 null")
    void fetchAndSave_dartApiException_fetchedAtRecordedTextNull() {
        when(dartDocumentClient.fetchDocumentBytes(anyString()))
                .thenThrow(new DartApiException("EMPTY", "no document for rcept_no"));

        disclosureContentService.fetchAndSave(testDisclosure.getId());

        Disclosure updated = disclosureRepository.findById(testDisclosure.getId()).orElseThrow();
        assertThat(updated.getContentText()).isNull();
        assertThat(updated.getContentFetchedAt()).isNotNull();
    }

    @Test
    @DisplayName("RestClientException(일시 실패) — content_fetched_at은 null 유지(재시도 허용)")
    void fetchAndSave_restClientException_fetchedAtRemainsNull() {
        when(dartDocumentClient.fetchDocumentBytes(anyString()))
                .thenThrow(new RestClientException("connection timeout"));

        disclosureContentService.fetchAndSave(testDisclosure.getId());

        Disclosure updated = disclosureRepository.findById(testDisclosure.getId()).orElseThrow();
        assertThat(updated.getContentText()).isNull();
        assertThat(updated.getContentFetchedAt()).isNull();
    }

    @Test
    @DisplayName("멱등 — content_fetched_at이 이미 있으면 fetch 없이 skip")
    void fetchAndSave_alreadyFetched_noFetchCall() {
        // 이미 fetch 완료된 공시를 시뮬레이션
        testDisclosure.applyContentFetch("기존 본문", OffsetDateTime.now());
        disclosureRepository.save(testDisclosure);

        // dartDocumentClient 호출 없이 return
        disclosureContentService.fetchAndSave(testDisclosure.getId());

        Disclosure unchanged = disclosureRepository.findById(testDisclosure.getId()).orElseThrow();
        assertThat(unchanged.getContentText()).isEqualTo("기존 본문");
    }

    @Test
    @DisplayName("findPendingContentFetchIds — content_fetched_at IS NULL 공시만 반환")
    void findPendingContentFetchIds_returnsOnlyPending() {
        // testDisclosure: content_fetched_at = null (pending)
        // 두 번째: content_fetched_at 기록됨 (done)
        Disclosure fetched = disclosureRepository.save(Disclosure.builder()
                .rceptNo("20260601000002")
                .corpCode("00126380")
                .stockCode("005930")
                .corpName("삼성전자")
                .reportNm("사업보고서")
                .rceptDt(LocalDate.of(2026, 6, 1))
                .disclosureType("ANNUAL_REPORT")
                .collectedAt(OffsetDateTime.now())
                .build());
        fetched.applyContentFetch("본문", OffsetDateTime.now());
        disclosureRepository.save(fetched);

        List<Long> pendingIds = disclosureRepository.findPendingContentFetchIds(null, PageRequest.of(0, 100));

        assertThat(pendingIds).containsExactly(testDisclosure.getId());
    }
}
