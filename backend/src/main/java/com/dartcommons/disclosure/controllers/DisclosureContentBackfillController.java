package com.dartcommons.disclosure.controllers;

import com.dartcommons.disclosure.dto.ContentBackfillJobResponse;
import com.dartcommons.disclosure.dto.ContentBackfillJobStartResponse;
import com.dartcommons.disclosure.services.DisclosureContentBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/*
 * [목적] 공시 본문 fetch 백필 잡을 생성·트리거하고 진행률을 조회하는 관리자용 REST 엔드포인트.
 *       POST /admin/disclosures/content-backfill: 잡 원자 생성 + 비동기 실행(202 + jobId 반환).
 *       GET  /admin/disclosures/content-backfill/jobs/{jobId}: 잡 진행률 조회.
 * [이유] content-fetch-backfill-resilience Spec Card #4 + dc-review-code MEDIUM 이슈 수정:
 *       (1) 이전: isRunning() + createJob() 사이 TOCTOU → createAndStartAsync() 원자 메서드로 해소.
 *       (2) 이전: Map<String, Object> 응답 → 타입 안전 ContentBackfillJobStartResponse record.
 *       POST → 202 Accepted: 비동기 실행이므로 즉시 jobId 반환(클라이언트 폴링으로 진행률 확인).
 * [사이드 임팩트] /admin/** 경로는 SecurityConfig가 HTTP Basic(ROLE_ADMIN) 인증 강제.
 *               중복 POST 시 서비스 CAS(createAndStartAsync)가 빈 Optional 반환 → 409 Conflict.
 *               GET /status 엔드포인트(이전 구현)는 제거됨 — jobId 기반 GET으로 대체.
 * [수정 시 고려사항] 잡 목록 GET(/admin/disclosures/content-backfill/jobs) 필요 시 별도 엔드포인트.
 *                  운영자 감사 로그(IP/사용자명)는 SecurityConfig AuditListener로 추가 권장.
 *                  409 응답도 타입 안전 DTO로 교체 시 공통 ErrorResponse record 도입.
 */
@RestController
@RequestMapping("/admin/disclosures/content-backfill")
@RequiredArgsConstructor
public class DisclosureContentBackfillController {

    private final DisclosureContentBackfillService backfillService;

    /**
     * 백필 잡 원자 생성 + 비동기 실행 시작.
     * 이미 실행 중이면 409 반환(중복 시작 없음).
     * 클라이언트는 반환된 jobId로 GET /jobs/{jobId} 폴링하여 진행률 확인.
     */
    @PostMapping
    public ResponseEntity<?> createAndStart() {
        return backfillService.createAndStartAsync()
                .map(job -> ResponseEntity.accepted()
                        .body((Object) new ContentBackfillJobStartResponse(
                                job.getJobId(),
                                job.getStatus().name(),
                                "Backfill started asynchronously. Poll GET /jobs/{jobId} for progress."
                        )))
                .orElseGet(() -> ResponseEntity.status(409)
                        .body(Map.of(
                                "error", "ALREADY_RUNNING",
                                "message", "Backfill already in progress. Check running job for progress."
                        )));
    }

    /**
     * 잡 진행률 조회 — processed/targeted/failed/lastProcessedId/status.
     * jobId는 POST 응답 또는 서버 로그에서 확인 가능.
     */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<ContentBackfillJobResponse> getJob(@PathVariable UUID jobId) {
        return backfillService.findByJobId(jobId)
                .map(job -> ResponseEntity.ok(ContentBackfillJobResponse.from(job)))
                .orElse(ResponseEntity.notFound().build());
    }
}
