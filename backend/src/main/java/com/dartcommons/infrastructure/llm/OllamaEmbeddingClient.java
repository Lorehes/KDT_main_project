package com.dartcommons.infrastructure.llm;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/*
 * [목적] Ollama 임베딩 모델(nomic-embed-text) 호출 — EmbeddingClient 구현체.
 *       LangChain4j OllamaEmbeddingModel을 래핑해 infrastructure 레이어에 SDK 의존성 격리.
 * [이유] EMBEDDING_PROVIDER=ollama 시 활성화 — 기본값은 mock(개발/CI).
 *       LangChain4j BOM 1.0.0이 OllamaEmbeddingModel 버전을 통합 관리(CLAUDE.md §2).
 *       maxRetries로 Ollama 일시 장애 시 자동 재시도.
 * [사이드 임팩트] 첫 호출 시 Ollama가 모델을 메모리에 로드 — 콜드 스타트 지연 가능(수십 초).
 *               임베딩 차원(768, nomic-embed-text)이 Chroma 컬렉션 차원과 일치해야 함.
 *               모델 교체 시 기존 Chroma 컬렉션 삭제 후 재생성 필요(차원 불일치 → Chroma 오류).
 * [수정 시 고려사항] 임베딩 모델 교체(e.g., mxbai-embed-large 1024차원) 시 CHROMA_COLLECTION 환경변수
 *                  도 변경해 별도 컬렉션을 사용할 것 — 기존 768차원 벡터와 호환 불가.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.llm.embedding", name = "provider", havingValue = "ollama")
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final OllamaEmbeddingModel model;

    public OllamaEmbeddingClient(EmbeddingProperties props) {
        this.model = OllamaEmbeddingModel.builder()
                .baseUrl(props.baseUrl())
                .modelName(props.modelName())
                .timeout(Duration.ofMillis(props.timeoutMs()))
                .maxRetries(props.maxRetries())
                .build();
    }

    @Override
    public float[] embed(String text) {
        Embedding embedding = model.embed(text).content();
        return embedding.vector();
    }
}
