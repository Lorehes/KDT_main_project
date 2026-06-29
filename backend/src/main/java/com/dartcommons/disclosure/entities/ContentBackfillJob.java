package com.dartcommons.disclosure.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] DART 본문 fetch 백필 잡의 상태·진행률·재개 포인트 영속화 — content_backfill_jobs(V25) 매핑.
 * [이유] DisclosureContentBackfillService가 AtomicBoolean 단일 플래그만 유지하면 재시작 시 진행률 유실(12h+ 재처리).
 *       AnalysisJob(analysis 도메인, V13) 패턴을 disclosure 도메인에 적용.
 *       last_processed_id: content-fetch-backfill-pagination 커서(id ASC)의 재개 하한.
 *       재시작 후 running=false ∧ status=RUNNING → stale(크래시) 잡 → last_processed_id부터 이어받기.
 * [사이드 임팩트] 잡 행은 영구 보존(감사). targeted는 시작 시점 스냅샷 — 진행 중 추가 공시 미반영(정상).
 *               processed+failed가 targeted를 초과할 수 있음(커서 재개 중 신규 pending 공시 유입 시).
 * [수정 시 고려사항] status/CHECK 제약과 Status enum 정합 필수(V25 불변 — 컬럼 추가는 새 V{n}).
 *                  다중 인스턴스(Kubernetes) 도입 시 stale 판정 로직이 정상 RUNNING 잡을 오인 가능.
 *                  분산 락(content-backfill-distributed-lock Spec)이 이 갭을 메운다.
 */
@Entity
@Table(name = "content_backfill_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class ContentBackfillJob {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "targeted", nullable = false)
    private int targeted;

    @Column(name = "processed", nullable = false)
    private int processed;

    @Column(name = "failed", nullable = false)
    private int failed;

    /** 커서 재개 포인트 — 이 id 이후(id > lastProcessedId)부터 재처리. null이면 처음부터. */
    @Column(name = "last_processed_id")
    private Long lastProcessedId;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "finished_at")
    private OffsetDateTime finishedAt;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public static ContentBackfillJob create() {
        OffsetDateTime now = OffsetDateTime.now();
        return ContentBackfillJob.builder()
                .jobId(UUID.randomUUID())
                .status(Status.PENDING)
                .targeted(0)
                .processed(0)
                .failed(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void start(int targeted) {
        this.status = Status.RUNNING;
        this.targeted = targeted;
        this.startedAt = OffsetDateTime.now();
        this.updatedAt = this.startedAt;
    }

    /** 청크 완료마다 갱신 — lastProcessedId 전진 + 카운터 누적. */
    public void recordChunkProgress(int processedDelta, int failedDelta, Long lastId) {
        this.processed += processedDelta;
        this.failed += failedDelta;
        this.lastProcessedId = lastId;
        this.updatedAt = OffsetDateTime.now();
    }

    public void succeed() {
        this.status = Status.SUCCEEDED;
        this.finishedAt = OffsetDateTime.now();
        this.updatedAt = this.finishedAt;
    }

    public void fail(String error) {
        this.status = Status.FAILED;
        this.errorMessage = error == null ? null : (error.length() > 1000 ? error.substring(0, 1000) : error);
        this.finishedAt = OffsetDateTime.now();
        this.updatedAt = this.finishedAt;
    }
}
