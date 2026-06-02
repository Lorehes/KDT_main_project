package com.dartcommons.disclosure.controllers;

import com.dartcommons.disclosure.entities.BackfillJob;
import com.dartcommons.disclosure.services.BackfillJobService;
import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.disclosure.services.DisclosureBackfillService.BackfillResult;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] 임의 날짜 범위 공시 백필을 트리거하는 관리자용 REST 엔드포인트 — 동기/비동기 두 경로 제공.
 * [이유] 운영 시점에 3년치 등 과거 공시를 일괄 적재.
 *       동기: 짧은 범위·즉시 결과 필요 시 (POST /backfill).
 *       비동기: 긴 범위·HTTP 타임아웃 회피 필요 시 (POST /backfill/jobs → jobId 반환, GET /backfill/jobs/{id} 상태 조회).
 * [사이드 임팩트] `/admin/**` 경로는 SecurityConfig가 HTTP Basic 인증 강제(ROLE_ADMIN).
 *               비동기 잡은 @Async 별도 스레드에서 실행 — 컨트롤러는 202 즉시 반환.
 *               동일 범위 백필 중복 호출 방지는 본 컨트롤러에서 미제공 — rcept_no 멱등으로 데이터 안전성만 보장.
 * [수정 시 고려사항] 잡 cleanup, 중단 기능, 재시도는 후속 Spec(BackfillJobService에 추가).
 *                  요청자 감사 로그(IP/사용자명)는 운영 배포 시 필수.
 *                  큰 트래픽이 예상되면 ThreadPoolTaskExecutor 빈 설정으로 동시 잡 수 제한.
 */
@RestController
@RequestMapping("/admin/disclosures")
@RequiredArgsConstructor
public class DisclosureBackfillController {

    private final DisclosureBackfillService backfillService;
    private final BackfillJobService jobService;

    /**
     * 동기 백필 — 짧은 범위 또는 즉시 결과 필요 시.
     * 예: {@code POST /admin/disclosures/backfill?from=2024-01-01&to=2024-01-30&emitEvents=false}
     */
    @PostMapping("/backfill")
    public ResponseEntity<BackfillResult> backfill(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean emitEvents) {
        BackfillResult result = backfillService.backfill(from, to, emitEvents);
        return ResponseEntity.ok(result);
    }

    /**
     * 비동기 백필 잡 생성 — 즉시 202 + jobId 반환.
     * 예: {@code POST /admin/disclosures/backfill/jobs?from=2023-06-01&to=2026-06-01&emitEvents=false}
     */
    @PostMapping("/backfill/jobs")
    public ResponseEntity<JobResponse> createJob(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean emitEvents) {
        BackfillJob job = jobService.createJob(from, to, emitEvents);
        jobService.runAsync(job.getJobId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(JobResponse.from(job));
    }

    /**
     * 잡 상태 조회.
     * 예: {@code GET /admin/disclosures/backfill/jobs/{jobId}}
     */
    @GetMapping("/backfill/jobs/{jobId}")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID jobId) {
        return jobService.findByJobId(jobId)
                .map(JobResponse::from)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    public record JobResponse(
            UUID jobId,
            BackfillJob.Status status,
            LocalDate from,
            LocalDate to,
            Integer chunksTotal,
            int chunksDone,
            int fetched,
            int saved,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt,
            String errorMessage
    ) {
        public static JobResponse from(BackfillJob j) {
            return new JobResponse(
                    j.getJobId(), j.getStatus(), j.getFromDate(), j.getToDate(),
                    j.getChunksTotal(), j.getChunksDone(), j.getFetched(), j.getSaved(),
                    j.getStartedAt(), j.getFinishedAt(), j.getErrorMessage()
            );
        }
    }
}
