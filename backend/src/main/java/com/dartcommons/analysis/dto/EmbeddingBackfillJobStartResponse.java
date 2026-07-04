package com.dartcommons.analysis.dto;

import java.util.UUID;

/*
 * [목적] 임베딩 백필 잡 시작 성공(202 Accepted) 응답 DTO — jobId와 초기 상태 반환.
 * [이유] Map<String, Object> 대신 타입 안전 record — 오타·null 필드 컴파일 타임 방지.
 *       클라이언트는 jobId로 GET /jobs/{jobId}를 폴링해 진행률 확인(stage3-embedding-backfill Spec R6).
 * [사이드 임팩트] 없음. 관리자 전용 POST /admin/analysis/embedding-backfill 응답에만 사용.
 * [수정 시 고려사항] 409 응답은 별도 ErrorResponse record 또는 공통 ProblemDetail 사용 권장.
 */
public record EmbeddingBackfillJobStartResponse(
        UUID jobId,
        String status,
        String message
) {}
