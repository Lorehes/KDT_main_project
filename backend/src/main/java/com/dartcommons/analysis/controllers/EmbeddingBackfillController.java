package com.dartcommons.analysis.controllers;

import com.dartcommons.analysis.dto.EmbeddingBackfillJobResponse;
import com.dartcommons.analysis.dto.EmbeddingBackfillJobStartResponse;
import com.dartcommons.analysis.services.EmbeddingBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/*
 * [목적] Stage 3 임베딩 백필 잡을 생성·트리거하고 진행률을 조회하는 관리자용 REST 엔드포인트.
 *       POST /admin/analysis/embedding-backfill: 잡 원자 생성 + 비동기 실행(202 + jobId 반환).
 *       GET  /admin/analysis/embedding-backfill/jobs/{jobId}: 잡 진행률 조회.
 * [이유] stage3-embedding-backfill Spec R6.
 *       CAS 원자 메서드(createAndStartAsync)로 TOCTOU 없이 202/409 분기 — V25 DisclosureContentBackfillController 동일 패턴.
 *       POST → 202 Accepted: 비동기 실행이므로 즉시 jobId 반환(클라이언트 폴링으로 진행률 확인).
 * [사이드 임팩트] /admin/** 경로는 SecurityConfig가 HTTP Basic(ROLE_ADMIN) 인증 강제.
 *               중복 POST 시 CAS가 빈 Optional 반환 → 409 Conflict.
 * [수정 시 고려사항] 잡 목록 GET(/admin/analysis/embedding-backfill/jobs) 필요 시 별도 엔드포인트 추가.
 *                  409 응답을 공통 ErrorResponse record로 교체 시 ProblemDetail 통합 권장.
 */
@RestController
@RequestMapping("/admin/analysis/embedding-backfill")
@RequiredArgsConstructor
public class EmbeddingBackfillController {

    private final EmbeddingBackfillService backfillService;

    /**
     * 임베딩 백필 잡 원자 생성 + 비동기 실행 시작.
     * 이미 실행 중이면 409 반환(중복 시작 없음).
     */
    @PostMapping
    public ResponseEntity<?> createAndStart() {
        return backfillService.createAndStartAsync()
                .map(job -> ResponseEntity.accepted()
                        .body((Object) new EmbeddingBackfillJobStartResponse(
                                job.getJobId(),
                                job.getStatus().name(),
                                "Embedding backfill started asynchronously. Poll GET /jobs/{jobId} for progress."
                        )))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of(
                                "error", "ALREADY_RUNNING",
                                "message", "Embedding backfill already in progress."
                        )));
    }

    /**
     * 잡 진행률 조회 — processed/targeted/failed/lastProcessedId/status.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<EmbeddingBackfillJobResponse> getJob(@PathVariable UUID jobId) {
        return backfillService.findByJobId(jobId)
                .map(job -> ResponseEntity.ok(EmbeddingBackfillJobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }
}
