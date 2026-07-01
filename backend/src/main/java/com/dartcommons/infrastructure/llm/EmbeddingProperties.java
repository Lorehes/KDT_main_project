package com.dartcommons.infrastructure.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * [목적] 임베딩 모델 설정 — application.yml의 dartcommons.llm.embedding.* 바인딩.
 * [이유] Stage 3 RAG 전용 임베딩 모델은 Stage 2 LLM과 서버/모델명이 다를 수 있음 — 별도 Properties 분리.
 *       코드 하드코딩 금지(CLAUDE.md §7) — 환경변수 주입으로 운영 교체.
 * [사이드 임팩트] LlmProperties(dartcommons.llm.*)와 공존 — prefix가 다르므로 바인딩 충돌 없음.
 *               provider=mock 시 MockEmbeddingClient 활성화 — Ollama 없이 테스트/개발 가능.
 *               maxChars: OllamaEmbeddingClient.embed()에서 절삭 한도로 사용 — 증분·백필 두 경로 모두 적용.
 * [수정 시 고려사항] modelName 변경 시 임베딩 차원이 달라지면 Chroma 컬렉션 재생성 필요.
 *                  maxChars는 모델 컨텍스트 토큰 한도와 연동 — 모델 교체 시 함께 조정(nomic-embed-text 2048토큰 ≈ 6000자).
 */
@ConfigurationProperties(prefix = "dartcommons.llm.embedding")
public record EmbeddingProperties(
        String provider,    // "ollama" | "mock" (기본 mock)
        String baseUrl,     // Ollama 서버 주소 (기본 http://localhost:11434)
        String modelName,   // 임베딩 모델명 (기본 nomic-embed-text, 768차원)
        int timeoutMs,      // HTTP 타임아웃 (기본 30000ms)
        int maxRetries,     // 재시도 횟수 (기본 2)
        int maxChars        // 임베딩 전 절삭 한도 (기본 6000자) — nomic-embed-text 2048토큰 안전 마진
) {}
