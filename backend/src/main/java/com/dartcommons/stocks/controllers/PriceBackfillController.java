package com.dartcommons.stocks.controllers;

import com.dartcommons.stocks.dto.PriceBackfillJobResponse;
import com.dartcommons.stocks.dto.PriceBackfillJobStartResponse;
import com.dartcommons.stocks.services.PriceBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/*
 * [목적] KRX 과거 주가 백필 잡을 생성·트리거하고 진행률을 조회하는 관리자용 REST 엔드포인트.
 *       POST /admin/stocks/price-backfill: 잡 원자 생성 + 비동기 실행(202 + jobId).
 *       GET  /admin/stocks/price-backfill/jobs/{jobId}: 진행률 조회.
 * [이유] krx-price-timeseries Wave B. CAS 원자 메서드(createAndStartAsync)로 TOCTOU 없이 202/409 분기 —
 *       V26 EmbeddingBackfillController 동일 패턴.
 * [사이드 임팩트] /admin/** 경로는 SecurityConfig가 HTTP Basic(ROLE_ADMIN) 강제. 중복 POST → 409.
 * [수정 시 고려사항] 잡 목록 GET 필요 시 별도 엔드포인트. 409는 공통 ProblemDetail 통합 권장.
 */
@RestController
@RequestMapping("/admin/stocks/price-backfill")
@RequiredArgsConstructor
public class PriceBackfillController {

    private final PriceBackfillService backfillService;

    @PostMapping
    public ResponseEntity<?> createAndStart() {
        return backfillService.createAndStartAsync()
                .map(job -> ResponseEntity.accepted()
                        .body((Object) new PriceBackfillJobStartResponse(
                                job.getJobId(),
                                job.getStatus().name(),
                                "Price backfill started asynchronously. Poll GET /jobs/{jobId} for progress."
                        )))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of(
                                "error", "ALREADY_RUNNING",
                                "message", "Price backfill already in progress."
                        )));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<PriceBackfillJobResponse> getJob(@PathVariable UUID jobId) {
        return backfillService.findByJobId(jobId)
                .map(job -> ResponseEntity.ok(PriceBackfillJobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }
}
