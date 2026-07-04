package com.dartcommons.analysis.controllers;

import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.analysis.services.AnalysisBackfillService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] analysis Stage 2~5 백필 트리거 관리자 REST. wave 3은 Stage 2만 사용.
 *       POST /admin/analysis/backfill/jobs — 잡 생성 + 즉시 202 + jobId 반환 (비동기 실행).
 *       GET  /admin/analysis/backfill/jobs/{jobId} — 진행률 조회.
 * [이유] 백필 1회당 91k건 × Stage 2 LLM 호출 = 시간 단위 → HTTP 타임아웃 회피. 잡 ID로 폴링.
 * [사이드 임팩트] `/admin/**` 경로라 SecurityConfig가 HTTP Basic 강제(ROLE_ADMIN).
 *               동일 범위 중복 호출 방지는 미제공 — AnalysisResult.disclosureId UNIQUE로 데이터 무결성만 보장.
 * [수정 시 고려사항] 잡 중단/cleanup, 재시도, 요청자 감사 로그는 후속 Spec.
 *                  파라미터 검증은 Service 계층에서 수행(IllegalArgumentException → 400 처리는 GlobalExceptionHandler 후속).
 */
@RestController
@RequestMapping("/admin/analysis")
public class AnalysisBackfillController {

    private final AnalysisBackfillService service;

    public AnalysisBackfillController(AnalysisBackfillService service) {
        this.service = service;
    }

    /**
     * 비동기 백필 잡 생성.
     * idFrom/idTo는 disclosure ID 범위 — 둘 다 null이면 전체 미분석 공시 대상.
     * 예: {@code POST /admin/analysis/backfill/jobs?chunkSize=100}
     *     {@code POST /admin/analysis/backfill/jobs?idFrom=1&idTo=10000&chunkSize=50}
     */
    @PostMapping("/backfill/jobs")
    public ResponseEntity<JobResponse> createJob(
            @RequestParam(required = false) Long idFrom,
            @RequestParam(required = false) Long idTo,
            @RequestParam(defaultValue = "100") int chunkSize) {
        AnalysisJob job = service.createJob(idFrom, idTo, chunkSize);
        service.runAsync(job.getJobId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
    }

    @GetMapping("/backfill/jobs/{jobId}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID jobId) {
        return service.findByJobId(jobId)
                .map(JobResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record JobResponse(
            UUID jobId,
            AnalysisJob.Status status,
            short stage,
            Long disclosureIdFrom,
            Long disclosureIdTo,
            int chunkSize,
            Integer chunksTotal,
            int chunksDone,
            int targeted,
            int analyzed,
            int failed,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String errorMessage
    ) {
        public static JobResponse from(AnalysisJob j) {
            return new JobResponse(
                    j.getJobId(),
                    j.getStatus(),
                    j.getStage(),
                    j.getDisclosureIdFrom(),
                    j.getDisclosureIdTo(),
                    j.getChunkSize(),
                    j.getChunksTotal(),
                    j.getChunksDone(),
                    j.getTargeted(),
                    j.getAnalyzed(),
                    j.getFailed(),
                    j.getStartedAt(),
                    j.getFinishedAt(),
                    j.getErrorMessage()
            );
        }
    }
}
