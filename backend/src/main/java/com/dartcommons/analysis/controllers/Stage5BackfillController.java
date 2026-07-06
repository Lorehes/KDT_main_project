package com.dartcommons.analysis.controllers;

import com.dartcommons.analysis.services.Stage5BackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/*
 * [목적] Stage 5 백필 잡 생성·트리거·진행률 조회 관리자 REST 엔드포인트.
 *       POST /admin/analysis/stage5-backfill: 잡 생성 + 비동기 실행(202 + jobId).
 *       GET  /admin/analysis/stage5-backfill/jobs/{jobId}: 진행률 조회.
 * [이유] analysis-stage5-financial-industry Spec 카드 #11. Stage4BackfillController 패턴 답습.
 *       /admin/** SecurityConfig HTTP Basic(ROLE_ADMIN) 인증 자동 적용.
 * [사이드 임팩트] 실행 중 중복 POST 시 409. 실행 전 재무 스냅샷 시드 백필 선행 필요(스냅샷 없으면 전건 skip).
 * [수정 시 고려사항] 야간 자동 스케줄 필요 시 @Scheduled + createAndStartAsync() 조합 추가 가능.
 */
@RestController
@RequestMapping("/admin/analysis/stage5-backfill")
@RequiredArgsConstructor
public class Stage5BackfillController {

    private final Stage5BackfillService backfillService;

    @PostMapping
    public ResponseEntity<?> createAndStart() {
        return backfillService.createAndStartAsync()
                .map(job -> ResponseEntity.accepted()
                        .body((Object) Map.of(
                                "jobId", job.getJobId(),
                                "status", job.getStatus().name(),
                                "message", "Stage 5 backfill started. Poll GET /jobs/{jobId} for progress."
                        )))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of(
                                "error", "ALREADY_RUNNING",
                                "message", "Stage 5 backfill already in progress."
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
