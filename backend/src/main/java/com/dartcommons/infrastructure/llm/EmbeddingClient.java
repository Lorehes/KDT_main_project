package com.dartcommons.infrastructure.llm;

/*
 * [목적] 텍스트 → 임베딩 벡터 변환 추상화 — analysis 도메인이 LangChain4j OllamaEmbeddingModel에 직접 의존하지 않도록 격리.
 * [이유] CLAUDE.md §3-2 도메인 격리 — 임베딩 모델(Ollama/OpenAI 등) 교체 시 Stage3RagService 코드 무변경.
 *       MockEmbeddingClient로 Ollama 없이 단위 테스트 가능.
 * [사이드 임팩트] OllamaEmbeddingClient는 EMBEDDING_PROVIDER=ollama 시 활성화.
 *               MockEmbeddingClient는 EMBEDDING_PROVIDER=mock(기본) 시 폴백.
 * [수정 시 고려사항] 반환 float[]의 차원(dimension)은 모델에 따라 다름 —
 *                  nomic-embed-text: 768차원. 모델 교체 시 Chroma 컬렉션 재생성 필요(차원 불일치 오류).
 */
public interface EmbeddingClient {

    /**
     * 텍스트를 임베딩 벡터로 변환.
     *
     * @param text 임베딩할 텍스트 (공시 본문 — content_text 필드)
     * @return     float[] 임베딩 벡터 (차원 수는 모델 설정에 의존)
     * @throws RuntimeException 임베딩 모델 호출 실패 시
     */
    float[] embed(String text);
}
