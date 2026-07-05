package com.dartcommons.analysis.controllers;

import com.dartcommons.analysis.services.Stage4BackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/*
 * [목적] Stage 4 백필 잡 생성·트리거·진행률 조회 관리자 REST 엔드포인트.
 *       POST /admin/analysis/stage4-backfill: 잡 생성 + 비동기 실행(202 + jobId).
 *       GET  /admin/analysis/stage4-backfill/jobs/{jobId}: 진행률 조회.
 * [이유] analysis-stage4-llm-final Spec R7. EmbeddingBackfillController 패턴 답습.
 *       /admin/** SecurityConfig HTTP Basic(ROLE_ADMIN) 인증 자동 적용.
 * [사이드 임팩트] 실행 중 중복 POST 시 409 반환. OpenRouter 일 예산 소진 시 잡이 조기 중단(FAILED) — 재시작 후 재호출로 이어서 진행.
 * [수정 시 고려사항] 야간 자동 스케줄이 필요하면 @Scheduled + createAndStartAsync() 조합으로 추가 가능.
 */
@RestController
@RequestMapping("/admin/analysis/stage4-backfill")
@RequiredArgsConstructor
public class Stage4BackfillController {

    private final Stage4BackfillService backfillService;

    @PostMapping
    public ResponseEntity<?> createAndStart() {
        return backfillService.createAndStartAsync()
                .map(job -> ResponseEntity.accepted()
                        .body((Object) Map.of(
                                "jobId", job.getJobId(),
                                "status", job.getStatus().name(),
                                "message", "Stage 4 backfill started. Poll GET /jobs/{jobId} for progress."
                        )))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of(
                                "error", "ALREADY_RUNNING",
                                "message", "Stage 4 backfill already in progress."
                        )));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> getJob(@PathVariable UUID jobId) {
        return backfillService.findByJobId(jobId)
                .map(job -> ResponseEntity.ok(Map.of(
                        "jobId", job.getJobId(),
                        "status", job.getStatus().name(),
                        "processed", job.getAnalyzed(),
                        "targeted", job.getTargeted(),
                        "failed", job.getFailed()
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
