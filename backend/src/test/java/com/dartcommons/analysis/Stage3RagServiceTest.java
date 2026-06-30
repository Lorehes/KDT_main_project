package com.dartcommons.analysis;

import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.analysis.services.Stage3RagService;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.chroma.ChromaClient;
import com.dartcommons.infrastructure.chroma.ChromaProperties;
import com.dartcommons.infrastructure.llm.EmbeddingClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/*
 * [목적] Stage3RagService 이중 쿼리 파티셔닝·임계치 필터·자신 제외 로직 단위 검증.
 *       EmbeddingClient, ChromaClient, DisclosureRepository를 Mock으로 대체 — Ollama/Chroma 없이 실행.
 * [이유] Stage 3의 핵심은 Java 포스트 필터 로직(sameCorp max5/otherCorp max5/자신 제외) —
 *       Chroma 유사도 계산은 인프라 책임이므로 단위 테스트 범위 밖.
 *       Mock DB 금지 원칙(CLAUDE.md §6-6)은 통합 테스트 대상 — 이 테스트는 단위(Spring 컨텍스트 없음).
 * [사이드 임팩트] @Async 어노테이션은 Spring 컨텍스트 없이 처리되지 않음 — upsert()가 동기 실행됨.
 *               실제 Chroma 유사도 검증은 Testcontainers 기반 통합 테스트(향후 Stage3IT)에서 커버.
 * [수정 시 고려사항] PARTITION_LIMIT(5), DUAL_QUERY_LIMIT(10) 상수 변경 시 테스트 기대값 함께 갱신.
 *                  ChromaProperties는 record — Mockito mock() 대신 인라인 생성.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class Stage3RagServiceTest {

    @Mock private EmbeddingClient embeddingClient;
    @Mock private ChromaClient chromaClient;
    @Mock private DisclosureRepository disclosureRepository;

    private Stage3RagService service;
    private static final float[] DUMMY_VECTOR = new float[768];
    private static final double THRESHOLD = 0.7;

    @BeforeEach
    void setUp() {
        ChromaProperties props = new ChromaProperties(true, "http://localhost:8001",
                "disclosure_embeddings", THRESHOLD, 10000);
        service = new Stage3RagService(embeddingClient, chromaClient, disclosureRepository, props);
        when(embeddingClient.embed(anyString())).thenReturn(DUMMY_VECTOR);
    }

    // ── upsert ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("upsert — contentText가 있으면 embed + chromaClient.upsert 호출")
    void upsert_withContentText_callsEmbedAndUpsert() {
        Disclosure d = makeDisclosure(1L, "A001", "005930", "삼성전자", "MERGER", "공시 본문");
        when(disclosureRepository.findById(1L)).thenReturn(Optional.of(d));

        service.upsert(1L);

        verify(embeddingClient).embed("공시 본문");
        verify(chromaClient).upsert(eq("A001"), eq(DUMMY_VECTOR), argThat(m ->
                "005930".equals(m.get("corp_code")) && "MERGER".equals(m.get("disclosure_type"))
        ));
    }

    @Test
    @DisplayName("upsert — contentText null이면 embed/upsert 미호출(스킵)")
    void upsert_withNullContent_skips() {
        Disclosure d = makeDisclosure(2L, "A002", "005930", "삼성전자", "MERGER", null);
        when(disclosureRepository.findById(2L)).thenReturn(Optional.of(d));

        service.upsert(2L);

        verify(embeddingClient, never()).embed(any());
        verify(chromaClient, never()).upsert(any(), any(), any());
    }

    @Test
    @DisplayName("upsert — disclosure 없으면 embed/upsert 미호출")
    void upsert_disclosureNotFound_skips() {
        when(disclosureRepository.findById(99L)).thenReturn(Optional.empty());

        service.upsert(99L);

        verify(chromaClient, never()).upsert(any(), any(), any());
    }

    // ── findSimilar ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("findSimilar — sameCorp max5 + otherCorp max5 파티셔닝, 유사도 내림차순")
    void findSimilar_dualPartition_respectedAndSortedByScore() {
        Disclosure target = makeDisclosure(10L, "T001", "005930", "삼성전자", "MERGER", "본문");
        when(disclosureRepository.findById(10L)).thenReturn(Optional.of(target));

        // sameCorp 3건 + otherCorp 4건 → 파티셔닝 결과 sameCorp3 + otherCorp4 = 7건
        List<ChromaClient.SimilarResult> raw = List.of(
                result("R1", 0.95, "005930", "삼성전자", "MERGER"),
                result("R2", 0.90, "005930", "삼성전자", "MERGER"),
                result("R3", 0.85, "005930", "삼성전자", "MERGER"),
                result("R4", 0.93, "000660", "SK하이닉스", "MERGER"),
                result("R5", 0.88, "000660", "SK하이닉스", "MERGER"),
                result("R6", 0.80, "000660", "SK하이닉스", "MERGER"),
                result("R7", 0.75, "035420", "NAVER", "MERGER")
        );
        when(chromaClient.query(eq(DUMMY_VECTOR), eq(Map.of("disclosure_type", "MERGER")),
                eq(10), eq(THRESHOLD))).thenReturn(raw);

        List<SimilarDisclosureItem> result = service.findSimilar(10L);

        assertThat(result).hasSize(7);
        // 유사도 내림차순 검증 (첫 번째 > 두 번째)
        assertThat(result.get(0).similarityScore()).isGreaterThanOrEqualTo(result.get(1).similarityScore());
        assertThat(result.get(1).similarityScore()).isGreaterThanOrEqualTo(result.get(2).similarityScore());
    }

    @Test
    @DisplayName("findSimilar — 자신(rceptNo=T001)은 결과에서 제외")
    void findSimilar_selfExcluded() {
        Disclosure target = makeDisclosure(10L, "T001", "005930", "삼성전자", "MERGER", "본문");
        when(disclosureRepository.findById(10L)).thenReturn(Optional.of(target));

        List<ChromaClient.SimilarResult> raw = List.of(
                result("T001", 1.0, "005930", "삼성전자", "MERGER"),  // 자신
                result("R1",   0.9, "000660", "SK하이닉스", "MERGER")
        );
        when(chromaClient.query(any(), any(), anyInt(), anyDouble())).thenReturn(raw);

        List<SimilarDisclosureItem> result = service.findSimilar(10L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).rceptNo()).isEqualTo("R1");
    }

    @Test
    @DisplayName("findSimilar — sameCorp 결과가 5건 초과 시 5건만 포함")
    void findSimilar_sameCorpCappedAt5() {
        Disclosure target = makeDisclosure(10L, "T001", "005930", "삼성전자", "MERGER", "본문");
        when(disclosureRepository.findById(10L)).thenReturn(Optional.of(target));

        // sameCorp 7건
        List<ChromaClient.SimilarResult> raw = List.of(
                result("R1", 0.99, "005930", "삼성전자", "MERGER"),
                result("R2", 0.98, "005930", "삼성전자", "MERGER"),
                result("R3", 0.97, "005930", "삼성전자", "MERGER"),
                result("R4", 0.96, "005930", "삼성전자", "MERGER"),
                result("R5", 0.95, "005930", "삼성전자", "MERGER"),
                result("R6", 0.94, "005930", "삼성전자", "MERGER"),
                result("R7", 0.93, "005930", "삼성전자", "MERGER")
        );
        when(chromaClient.query(any(), any(), anyInt(), anyDouble())).thenReturn(raw);

        List<SimilarDisclosureItem> result = service.findSimilar(10L);

        // sameCorp max5 — R6, R7 제외
        assertThat(result).hasSize(5);
        assertThat(result.stream().map(SimilarDisclosureItem::rceptNo).toList())
                .containsExactlyInAnyOrder("R1", "R2", "R3", "R4", "R5");
    }

    @Test
    @DisplayName("findSimilar — contentText null이면 빈 리스트 반환")
    void findSimilar_noContentText_returnsEmpty() {
        Disclosure d = makeDisclosure(5L, "N001", "005930", "삼성전자", "MERGER", null);
        when(disclosureRepository.findById(5L)).thenReturn(Optional.of(d));

        List<SimilarDisclosureItem> result = service.findSimilar(5L);

        assertThat(result).isEmpty();
        verify(chromaClient, never()).query(any(), any(), anyInt(), anyDouble());
    }

    @Test
    @DisplayName("findSimilar — ChromaClient 예외 시 빈 리스트 반환(서비스 비차단)")
    void findSimilar_chromaException_returnsEmpty() {
        Disclosure d = makeDisclosure(6L, "X001", "005930", "삼성전자", "MERGER", "본문");
        when(disclosureRepository.findById(6L)).thenReturn(Optional.of(d));
        when(chromaClient.query(any(), any(), anyInt(), anyDouble()))
                .thenThrow(new RuntimeException("Chroma 연결 실패"));

        List<SimilarDisclosureItem> result = service.findSimilar(6L);

        assertThat(result).isEmpty();
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    private static Disclosure makeDisclosure(Long id, String rceptNo, String corpCode,
                                             String corpName, String type, String content) {
        Disclosure d = Disclosure.builder()
                .rceptNo(rceptNo)
                .corpCode(corpCode)
                .corpName(corpName)
                .reportNm("보고서명")
                .rceptDt(LocalDate.of(2024, 1, 1))
                .disclosureType(type)
                .contentText(content)
                .collectedAt(OffsetDateTime.now())
                .createdAt(OffsetDateTime.now())
                .build();
        // reflection으로 id 주입 (JPA @GeneratedValue — Builder에서 설정 불가)
        try {
            Field idField = Disclosure.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(d, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        return d;
    }

    private static ChromaClient.SimilarResult result(String id, double score,
                                                       String corpCode, String corpName, String type) {
        return new ChromaClient.SimilarResult(id, score, Map.of(
                "corp_code", corpCode,
                "corp_name", corpName,
                "disclosure_type", type,
                "rcept_dt", "2024-01-01",
                "disclosure_id", "100"
        ));
    }
}
