package com.dartcommons.infrastructure.llm;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;

/*
 * [목적] Ollama 임베딩 모델(nomic-embed-text) 호출 — EmbeddingClient 구현체.
 *       LangChain4j OllamaEmbeddingModel을 래핑해 infrastructure 레이어에 SDK 의존성 격리.
 *       embed() 호출 전 텍스트를 maxChars(기본 6000자)로 절삭 — 컨텍스트 초과 500 오류 방어.
 * [이유] EMBEDDING_PROVIDER=ollama 시 활성화 — 기본값은 mock(개발/CI).
 *       LangChain4j BOM 1.0.0이 OllamaEmbeddingModel 버전을 통합 관리(CLAUDE.md §2).
 *       maxRetries로 Ollama 일시 장애 시 자동 재시도.
 *       절삭을 이 클래스에 둔 이유: 증분(Stage3RagService.upsert/findSimilar)·백필 두 경로가 모두
 *       EmbeddingClient.embed()를 경유하므로 단일 지점 수정으로 두 경로의 500 버그를 동시 해소(R1).
 * [사이드 임팩트] 첫 호출 시 Ollama가 모델을 메모리에 로드 — 콜드 스타트 지연 가능(수십 초).
 *               임베딩 차원(768, nomic-embed-text)이 Chroma 컬렉션 차원과 일치해야 함.
 *               모델 교체 시 기존 Chroma 컬렉션 삭제 후 재생성 필요(차원 불일치 → Chroma 오류).
 *               6000자↑ 문서(전체의 31.6%)는 앞부분만 임베딩 — 뒷부분 맥락 유실 수용(Spec 결정).
 * [수정 시 고려사항] 임베딩 모델 교체(e.g., mxbai-embed-large 1024차원) 시 CHROMA_COLLECTION 환경변수
 *                  도 변경해 별도 컬렉션을 사용할 것 — 기존 768차원 벡터와 호환 불가.
 *                  maxChars는 EMBEDDING_MAX_CHARS 환경변수로 조정 가능 — 모델 교체 시 함께 재산정.
 *                  ponytail: 단순 substring. 의미 손실 허용. 문장경계/청크 분할은 정확도 이슈 시 후속 승급.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.llm.embedding", name = "provider", havingValue = "ollama")
public class OllamaEmbeddingClient implements EmbeddingClient {

    private final OllamaEmbeddingModel model;
    private final int maxChars;

    public OllamaEmbeddingClient(EmbeddingProperties props) {
        this.model = OllamaEmbeddingModel.builder()
                .baseUrl(props.baseUrl())
                .modelName(props.modelName())
                .timeout(Duration.ofMillis(props.timeoutMs()))
                .maxRetries(props.maxRetries())
                .build();
        this.maxChars = props.maxChars();
    }

    @Override
    public float[] embed(String text) {
        String input = (text != null && text.length() > maxChars) ? text.substring(0, maxChars) : text;
        Embedding embedding = model.embed(input).content();
        return embedding.vector();
    }

    /** 절삭 로직 단위 테스트용 — package-private. */
    static String truncate(String text, int maxChars) {
        return (text != null && text.length() > maxChars) ? text.substring(0, maxChars) : text;
    }
}
