package com.dartcommons.infrastructure.chroma;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * [목적] Chroma 벡터 DB 연결·쿼리 설정 — application.yml의 dartcommons.chroma.* 바인딩.
 * [이유] 환경변수 주입 전용 — 코드 하드코딩 금지(CLAUDE.md §7).
 *       similarityThreshold는 Stage 3 RAG 강제 매칭 방지 임계치(R8) — 기본 0.7.
 *       enabled=false 시 LangChain4jChromaClient Bean이 생성되지 않음(@ConditionalOnProperty).
 * [사이드 임팩트] enabled=false일 때 MockChromaClient 활성화 — 프로덕션은 CHROMA_ENABLED=true 필수.
 *               baseUrl 변경 시 ChromaCollectionBootstrapper의 RestClient도 동일 값 참조.
 * [수정 시 고려사항] collectionName 변경 시 기존 Chroma 컬렉션은 마이그레이션 없이 유실 —
 *                  반드시 새 컬렉션으로 임베딩 재생성 후 변경할 것.
 */
@ConfigurationProperties(prefix = "dartcommons.chroma")
public record ChromaProperties(
        boolean enabled,            // true: LangChain4jChromaClient 활성화, false: MockChromaClient
        String baseUrl,             // Chroma REST API 주소 (기본 http://localhost:8001)
        String collectionName,      // 컬렉션명 (기본 disclosure_embeddings)
        double similarityThreshold, // 유사도 임계치 — 미만 결과 제외(환각 가드 R8)
        int timeoutMs               // HTTP 타임아웃 (기본 10000ms)
) {}
