package com.dartcommons.infrastructure.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] OllamaEmbeddingClient.truncate() 절삭 로직 단위 검증.
 *       실측: nomic-embed-text는 ~6700자 초과 시 HTTP 500("input length exceeds context length") 반환.
 *       이 테스트가 실패하면 절삭 로직이 깨진 것 — Ollama 실제 호출 없이 보호.
 * [이유] 절삭 로직이 올바르지 않으면 31.6%의 공시(6000자↑)가 임베딩 실패하고 Stage 3 RAG가 무동작.
 *       Ollama 없이 package-private truncate()를 직접 테스트해 빠른 피드백 보장.
 * [사이드 임팩트] 없음. 프로덕션 코드 미변경.
 * [수정 시 고려사항] maxChars 기본값(6000) 변경 시 이 테스트도 함께 조정.
 */
class EmbeddingTruncationTest {

    @Test
    void 한도_이하_텍스트는_그대로_반환() {
        String text = "a".repeat(5999);
        assertThat(OllamaEmbeddingClient.truncate(text, 6000)).isEqualTo(text);
    }

    @Test
    void 정확히_한도_텍스트는_그대로_반환() {
        String text = "a".repeat(6000);
        assertThat(OllamaEmbeddingClient.truncate(text, 6000)).isEqualTo(text);
    }

    @Test
    void 한도_초과_텍스트는_6000자로_절삭() {
        String text = "a".repeat(10000);
        String result = OllamaEmbeddingClient.truncate(text, 6000);
        assertThat(result).hasSize(6000);
        assertThat(result).isEqualTo(text.substring(0, 6000));
    }

    @Test
    void null_입력은_null_반환() {
        assertThat(OllamaEmbeddingClient.truncate(null, 6000)).isNull();
    }

    @Test
    void 한국어_텍스트_6000자_초과시_절삭() {
        // 한국어 문자도 charAt 기반 length()로 절삭 — 6700자 임계 실측(stage3-embedding-backfill Spec)
        String text = "가".repeat(7000);
        String result = OllamaEmbeddingClient.truncate(text, 6000);
        assertThat(result).hasSize(6000);
    }
}
