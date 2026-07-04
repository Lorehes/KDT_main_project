package com.dartcommons.disclosure.dto;

import com.dartcommons.disclosure.entities.ContentBackfillJob;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] ContentBackfillJob 엔티티를 REST 응답으로 직렬화 — 진행률·재개 포인트·완료 시각 제공.
 * [이유] 엔티티 직접 노출 방지(Jackson 직렬화 범위 통제) + 내부 PK(Long id) 미노출.
 *       processed/targeted로 진행률 표시 UI가 계산 가능. lastProcessedId는 운영자 디버깅용.
 * [사이드 임팩트] @JsonInclude(NON_NULL)로 null 필드 생략 — PENDING 잡은 startedAt/finishedAt 미포함.
 * [수정 시 고려사항] 진행률 % 계산은 클라이언트 위임(처리 도중 targeted가 실제 값으로 갱신되므로 서버 계산 불안정).
 *                  errorMessage는 운영자 전용 — 공개 API 전환 시 제거 또는 권한 분리.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentBackfillJobResponse(
        UUID jobId,
        String status,
        int processed,
        int targeted,
        int failed,
        Long lastProcessedId,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage
) {
    public static ContentBackfillJobResponse from(ContentBackfillJob job) {
        return new ContentBackfillJobResponse(
                job.getJobId(),
                job.getStatus().name(),
                job.getProcessed(),
                job.getTargeted(),
                job.getFailed(),
                job.getLastProcessedId(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getErrorMessage()
        );
    }
}
