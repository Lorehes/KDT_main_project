package com.dartcommons.infrastructure.chroma;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.chroma.ChromaEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/*
 * [목적] LangChain4j ChromaEmbeddingStore를 래핑해 ChromaClient 인터페이스 구현.
 *       도메인(analysis)이 LangChain4j 타입(Embedding, TextSegment, Filter)에 직접 의존하지 않도록 격리.
 * [이유] CLAUDE.md §3-2 — analysis → infrastructure SDK 직접 의존 금지.
 *       CHROMA_ENABLED=true 시에만 빈 등록 — false면 MockChromaClient 폴백.
 *       filterEquals 맵을 LangChain4j Filter(AND 체인)로 변환해 메타데이터 필터 지원.
 * [사이드 임팩트] ChromaEmbeddingStore 인스턴스는 싱글턴 — Chroma baseUrl/collectionName 변경 시 재기동 필요.
 *               upsert 시 addAll(List, List, List) 사용 — id + TextSegment(metadata) 동시 지정.
 *               1.0.0-beta5 기준 add(String id, Embedding, TextSegment) 메서드 없음 → addAll() 사용.
 * [수정 시 고려사항] filterEquals 값이 null이면 해당 키 필터를 건너뜀 — null-safe AND 체인 구현.
 *                  EmbeddingSearchRequest.builder().filter(null)은 전체 검색과 동일 (LangChain4j 내부 null 허용).
 *                  Chroma 0.5.23 버전에서 `/api/v1/` 경로 사용 — 버전 업그레이드 시 LangChain4j 버전도 함께 확인.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.chroma", name = "enabled", havingValue = "true")
public class LangChain4jChromaClient implements ChromaClient {

    private final ChromaEmbeddingStore store;

    public LangChain4jChromaClient(ChromaProperties props) {
        this.store = ChromaEmbeddingStore.builder()
                .baseUrl(props.baseUrl())
                .collectionName(props.collectionName())
                .timeout(Duration.ofMillis(props.timeoutMs()))
                .build();
    }

    @Override
    public void upsert(String id, float[] vector, Map<String, String> metadata) {
        Metadata lc4jMeta = new Metadata();
        metadata.forEach(lc4jMeta::put);
        TextSegment segment = TextSegment.from("", lc4jMeta);
        // LangChain4j 1.0.0-beta5: add(String,Embedding,TextSegment) 미존재 → addAll() 사용
        store.addAll(List.of(id), List.of(Embedding.from(vector)), List.of(segment));
    }

    @Override
    public List<SimilarResult> query(float[] queryVector, Map<String, String> filterEquals,
                                     int maxResults, double minScore) {
        Filter filter = buildAndFilter(filterEquals);

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(Embedding.from(queryVector))
                .maxResults(maxResults)
                .minScore(minScore)
                .filter(filter)
                .build();

        EmbeddingSearchResult<TextSegment> result = store.search(request);
        return result.matches().stream()
                .map(this::toSimilarResult)
                .toList();
    }

    private Filter buildAndFilter(Map<String, String> filterEquals) {
        if (filterEquals == null || filterEquals.isEmpty()) return null;
        Filter combined = null;
        for (Map.Entry<String, String> entry : filterEquals.entrySet()) {
            if (entry.getValue() == null) continue;
            Filter f = metadataKey(entry.getKey()).isEqualTo(entry.getValue());
            combined = (combined == null) ? f : combined.and(f);
        }
        return combined;
    }

    private SimilarResult toSimilarResult(EmbeddingMatch<TextSegment> match) {
        Map<String, String> meta = Map.of();
        if (match.embedded() != null && match.embedded().metadata() != null) {
            meta = match.embedded().metadata().toMap().entrySet().stream()
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            e -> String.valueOf(e.getValue())
                    ));
        }
        return new SimilarResult(match.embeddingId(), match.score(), meta);
    }
}
