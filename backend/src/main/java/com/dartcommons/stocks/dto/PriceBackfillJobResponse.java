package com.dartcommons.stocks.dto;

import com.dartcommons.stocks.entities.PriceBackfillJob;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] PriceBackfillJob 엔티티를 REST 응답으로 직렬화 — 진행률·재개 포인트·완료 시각 제공.
 * [이유] 엔티티 직접 노출 방지 + 내부 PK 미노출. processed/targeted(날짜 기준)로 진행률 계산.
 *       V26 EmbeddingBackfillJobResponse 패턴 — 커서가 날짜(lastProcessedDate)인 점만 상이.
 * [사이드 임팩트] @JsonInclude(NON_NULL)로 null 필드 생략(PENDING 잡은 startedAt 미포함).
 * [수정 시 고려사항] errorMessage는 운영자 전용 — 관리자(/admin) 경로 한정.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PriceBackfillJobResponse(
        UUID jobId,
        String status,
        int processed,
        int targeted,
        int failed,
        LocalDate lastProcessedDate,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        String errorMessage
) {
    public static PriceBackfillJobResponse from(PriceBackfillJob job) {
        return new PriceBackfillJobResponse(
                job.getJobId(),
                job.getStatus().name(),
                job.getProcessed(),
                job.getTargeted(),
                job.getFailed(),
                job.getLastProcessedDate(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getErrorMessage()
        );
    }
}
