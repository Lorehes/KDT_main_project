package com.dartcommons.analysis.controllers;

import com.dartcommons.analysis.entities.AnalysisJob;
import com.dartcommons.analysis.services.ReanalysisService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/*
 * [목적] charset 재수집 후 손상 본문 기반 분석을 재산출하는 관리자용 REST 엔드포인트.
 *       POST /admin/analysis/reanalyze — 잡 생성 + 비동기 실행(202 + jobId 반환).
 *       GET  /admin/analysis/reanalyze/jobs/{jobId} — 진행률 조회.
 * [이유] reanalyze-after-charset-recollection Spec 카드 #3.
 *       AnalysisBackfillController(미분석 백필)와 역할 분리 — 이쪽은 기존 분석 결과 삭제→재분석.
 * [사이드 임팩트] /admin/** 경로라 SecurityConfig HTTP Basic(ROLE_ADMIN) 인증 강제.
 *               중복 실행 방지: ReanalysisService AtomicBoolean CAS → 이미 실행 중이면 409.
 *               since 기본값: 2026-07-03T00:00:00Z(charset 재수집 시작 시점). 명시 지정 가능.
 * [수정 시 고려사항] since 파라미터로 재분석 범위 조정 가능(다음 재수집 배치에서 재사용).
 *                  잡 목록 GET, 취소 엔드포인트는 미구현 — 필요 시 별도 추가.
 */
@RestController
@RequestMapping("/admin/analysis/reanalyze")
public class ReanalysisController {

    // charset 재수집 시작 시점 — since 기본값
    private static final OffsetDateTime DEFAULT_SINCE =
            OffsetDateTime.parse("2026-07-03T00:00:00Z");

    private final ReanalysisService service;

    public ReanalysisController(ReanalysisService service) {
        this.service = service;
    }

    /**
     * 재분석 잡 생성 + 비동기 시작.
     * since: 재수집 기준 시점(기본 2026-07-03T00:00:00Z). content_fetched_at >= since인 공시 대상.
     * chunkSize: 청크 크기(기본 100, 최대 500).
     */
    @PostMapping
    public ResponseEntity<?> startReanalysis(
            @RequestParam(required = false) OffsetDateTime since,
            @RequestParam(defaultValue = "100") int chunkSize) {
        OffsetDateTime effectiveSince = since != null ? since : DEFAULT_SINCE;
        return service.createAndStartAsync(effectiveSince, chunkSize)
                .map(job -> ResponseEntity.status(HttpStatus.ACCEPTED)
                        .body((Object) JobResponse.from(job, effectiveSince)))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of(
                                "error", "ALREADY_RUNNING",
                                "message", "Reanalysis already in progress."
                        )));
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID jobId) {
        return service.findByJobId(jobId)
                .map(j -> ResponseEntity.ok(JobResponse.from(j, DEFAULT_SINCE)))
                .orElse(ResponseEntity.notFound().build());
    }

    public record JobResponse(
            UUID jobId,
            AnalysisJob.Status status,
            OffsetDateTime since,
            int chunkSize,
            int targeted,
            int analyzed,
            int failed,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt
    ) {
        static JobResponse from(AnalysisJob j, OffsetDateTime since) {
            return new JobResponse(
                    j.getJobId(), j.getStatus(), since, j.getChunkSize(),
                    j.getTargeted(), j.getAnalyzed(), j.getFailed(),
                    j.getStartedAt(), j.getFinishedAt()
            );
        }
    }
}
