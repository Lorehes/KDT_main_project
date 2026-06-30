package com.dartcommons.infrastructure.chroma;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/*
 * [목적] 앱 기동 시 Chroma 연결 확인 + disclosure_embeddings 컬렉션을 코사인(hnsw:space=cosine) 거리로 초기화.
 *       LangChain4j ChromaEmbeddingStore는 기본 L2 거리로 컬렉션을 생성하므로, 별도 API 호출로 코사인 강제 적용.
 * [이유] Chroma 0.5.x의 기본 거리 함수가 L2 — 코사인 거리는 임베딩 벡터의 의미 유사도에 더 적합.
 *       컬렉션이 이미 존재하면 409/400 응답 → 무시하고 계속 (멱등 부트스트랩).
 *       CHROMA_ENABLED=true 시에만 실행(@ConditionalOnProperty) — false면 빈 자체가 없음.
 * [사이드 임팩트] 기존 L2 컬렉션이 있으면 이 부트스트래퍼가 삭제하지 않음 — 수동으로 컬렉션 삭제 후 재기동 필요.
 *               ApplicationReadyEvent: 웹 서버 완전 기동 후 실행 — Chroma 컨테이너가 healthcheck 통과 후여야 연결됨.
 *               Chroma 연결 실패 시 경고 로그 후 계속 — 치명적 실패 아님(임베딩 비활성 상태로 운영 가능).
 * [수정 시 고려사항] Chroma 버전 업그레이드 시 API 경로(/api/v1/) 변경 여부 확인.
 *                  v0.5.x 기준 GET /api/v1/collections, POST /api/v1/collections 사용.
 */
@Component
@ConditionalOnProperty(prefix = "dartcommons.chroma", name = "enabled", havingValue = "true")
public class ChromaCollectionBootstrapper {

    private static final Logger log = LoggerFactory.getLogger(ChromaCollectionBootstrapper.class);

    private final ChromaProperties props;
    private final RestClient restClient;

    public ChromaCollectionBootstrapper(ChromaProperties props) {
        this.props = props;
        // CLAUDE.md §4: 외부 호출 타임아웃 필수
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.timeoutMs());
        factory.setReadTimeout(props.timeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initCollection() {
        try {
            checkHeartbeat();
            ensureCollection();
        } catch (Exception e) {
            // 치명적이지 않음 — Chroma 미기동 상태에서도 앱은 계속 동작 (Stage 3 비활성)
            log.warn("[ChromaBootstrap] Chroma 초기화 실패 — Stage 3 임베딩 비활성 상태로 계속. err={}", e.getMessage());
        }
    }

    private void checkHeartbeat() {
        restClient.get()
                .uri("/api/v1/heartbeat")
                .retrieve()
                .toBodilessEntity();
        log.info("[ChromaBootstrap] Chroma 연결 확인 — {}", props.baseUrl());
    }

    private void ensureCollection() {
        // 기존 컬렉션 목록 확인
        List<Map<String, Object>> collections = restClient.get()
                .uri("/api/v1/collections")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        boolean exists = collections != null && collections.stream()
                .anyMatch(c -> props.collectionName().equals(c.get("name")));

        if (exists) {
            log.info("[ChromaBootstrap] 컬렉션 이미 존재: {}", props.collectionName());
            return;
        }

        // 코사인 거리로 신규 생성
        try {
            restClient.post()
                    .uri("/api/v1/collections")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "name", props.collectionName(),
                            "metadata", Map.of("hnsw:space", "cosine")
                    ))
                    .retrieve()
                    .toBodilessEntity();
            log.info("[ChromaBootstrap] 컬렉션 생성(cosine): {}", props.collectionName());
        } catch (HttpClientErrorException.Conflict e) {
            // 409 — 동시 기동 시 다른 인스턴스가 먼저 생성. 정상.
            log.info("[ChromaBootstrap] 컬렉션 이미 생성됨(409 — 동시 기동 경쟁): {}", props.collectionName());
        }
    }
}
