package com.dartcommons.infrastructure.llm;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/*
 * [목적] EmbeddingClient Mock 구현 — Ollama 없이 테스트/로컬 개발 가능.
 *       768차원 정규화된 더미 벡터 반환 (nomic-embed-text와 동일 차원).
 * [이유] @ConditionalOnMissingBean: OllamaEmbeddingClient가 없을 때(provider=mock) 자동 등록.
 *       단위 테스트에서 Stage3RagService 로직을 Ollama 없이 검증할 수 있어야 함.
 * [사이드 임팩트] 더미 벡터는 모든 공시에 동일 → Chroma 유사도 검색이 무의미(랜덤 결과).
 *               EMBEDDING_PROVIDER=mock 인 프로덕션 배포는 Stage 3가 비활성 상태와 동등.
 * [수정 시 고려사항] 차원(768)이 nomic-embed-text와 동일해야 MockChromaClient와 함께 쓰는 단위 테스트에서
 *                  차원 불일치 오류가 발생하지 않음. 임베딩 모델 교체 시 함께 수정.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.llm.embedding", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingClient implements EmbeddingClient {

    private static final int DIMENSION = 768;

    @Override
    public float[] embed(String text) {
        // nomic-embed-text와 동일 768차원 — 단위 테스트에서 차원 불일치 방지
        float[] vector = new float[DIMENSION];
        float norm = 0f;
        for (int i = 0; i < DIMENSION; i++) {
            // 텍스트 해시 기반 시드로 테스트 간 재현성 확보 (랜덤보다 나음)
            vector[i] = (float) Math.sin(i + text.hashCode());
            norm += vector[i] * vector[i];
        }
        // L2 정규화 — 코사인 유사도 기반 Chroma와 일관성
        norm = (float) Math.sqrt(norm);
        if (norm > 0f) {
            for (int i = 0; i < DIMENSION; i++) {
                vector[i] /= norm;
            }
        }
        return vector;
    }
}
