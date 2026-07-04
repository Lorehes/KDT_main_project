package com.dartcommons.infrastructure.chroma;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * [목적] ChromaClient Mock 구현 — Chroma 없이 단위 테스트/로컬 개발 가능.
 *       인메모리 ConcurrentHashMap으로 임베딩 저장 + 필터 기반 조회.
 * [이유] @ConditionalOnMissingBean: LangChain4jChromaClient가 없을 때(CHROMA_ENABLED=false) 자동 등록.
 *       Stage3RagServiceTest에서 @MockBean 대신 실제 Mock 구현체를 사용해 검증 가능.
 * [사이드 임팩트] 프로세스 재시작 시 임베딩 소멸 — 완전한 인메모리 스토어.
 *               유사도 계산 없이 필터 일치 항목을 순서대로 반환(score=1.0 고정).
 *               CHROMA_ENABLED=true 상태에서도 LangChain4jChromaClient가 먼저 등록되므로 MockChromaClient는 무시됨.
 * [수정 시 고려사항] 테스트에서 stub 동작이 필요하면 @MockBean(MockChromaClient)보다 Mockito mock(ChromaClient)가 명확.
 *                  실제 유사도 검증이 필요한 통합 테스트는 Testcontainers로 Chroma를 직접 기동.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.chroma", name = "enabled", havingValue = "false", matchIfMissing = true)
public class MockChromaClient implements ChromaClient {

    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();
    private final Map<String, Map<String, String>> metadataStore = new ConcurrentHashMap<>();

    @Override
    public void upsert(String id, float[] vector, Map<String, String> metadata) {
        vectors.put(id, vector.clone());
        metadataStore.put(id, Collections.unmodifiableMap(new java.util.HashMap<>(metadata)));
    }

    @Override
    public List<SimilarResult> query(float[] queryVector, Map<String, String> filterEquals,
                                     int maxResults, double minScore) {
        List<SimilarResult> results = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : metadataStore.entrySet()) {
            if (matchesFilter(entry.getValue(), filterEquals)) {
                // Mock: 유사도 계산 없이 1.0 고정 반환 (단위 테스트 결정론적 동작)
                results.add(new SimilarResult(entry.getKey(), 1.0, entry.getValue()));
            }
            if (results.size() >= maxResults) break;
        }
        return results;
    }

    private boolean matchesFilter(Map<String, String> metadata, Map<String, String> filterEquals) {
        if (filterEquals == null || filterEquals.isEmpty()) return true;
        for (Map.Entry<String, String> f : filterEquals.entrySet()) {
            if (!f.getValue().equals(metadata.get(f.getKey()))) return false;
        }
        return true;
    }
}
