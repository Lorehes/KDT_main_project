package com.dartcommons.infrastructure.chroma;

import java.util.List;
import java.util.Map;

/*
 * [목적] Chroma 벡터 DB 접근 추상화 — analysis 도메인이 LangChain4j SDK에 직접 의존하지 않도록 격리.
 * [이유] CLAUDE.md §3-2 도메인 격리 원칙 — 도메인(analysis)이 인프라 SDK(LangChain4j/ChromaEmbeddingStore)에
 *       직접 의존하면 SDK 교체 시 도메인 코드 전면 수정 필요. 인터페이스 경유로 SDK 의존성을 infrastructure에 국한.
 *       MockChromaClient로 Chroma 없이 단위 테스트 가능 — 통합 테스트(Testcontainers)와 분리.
 * [사이드 임팩트] LangChain4jChromaClient는 Chroma 활성화(@ConditionalOnProperty) 시에만 빈 등록.
 *               MockChromaClient는 Chroma 비활성화(@ConditionalOnMissingBean) 시 폴백 — CI/개발 환경.
 * [수정 시 고려사항] query() 결과는 minScore 이상만 반환 — 호출자가 임계치를 추가로 필터링하지 않아도 됨.
 *                  upsert()는 동일 id 호출 시 덮어쓰기 (멱등) — rcept_no 기반 중복 방지.
 *                  filter 맵은 AND 조건 — OR/범위 조건 필요 시 인터페이스 확장 필요.
 */
public interface ChromaClient {

    record SimilarResult(
            String id,
            double score,
            Map<String, String> metadata
    ) {}

    /**
     * 임베딩 upsert — 동일 id는 덮어씀 (멱등).
     *
     * @param id       임베딩 고유 식별자 (rcept_no 사용)
     * @param vector   임베딩 벡터 (float[])
     * @param metadata 검색 필터용 메타데이터 (corp_code, disclosure_type, corp_name, rcept_dt, sentiment 등)
     */
    void upsert(String id, float[] vector, Map<String, String> metadata);

    /**
     * 유사 임베딩 검색 — filterEquals AND 조건 + minScore 이상만 반환.
     *
     * @param queryVector   쿼리 벡터
     * @param filterEquals  메타데이터 동등 조건 맵 (AND 결합)
     * @param maxResults    최대 반환 건수
     * @param minScore      유사도 임계치 (미만 제외)
     * @return              유사도 내림차순 정렬된 결과 목록
     */
    List<SimilarResult> query(float[] queryVector, Map<String, String> filterEquals,
                              int maxResults, double minScore);
}
