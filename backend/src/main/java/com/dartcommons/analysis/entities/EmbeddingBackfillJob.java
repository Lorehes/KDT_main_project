package com.dartcommons.analysis.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] Stage 3 RAG 임베딩 백필 잡의 상태·진행률·재개 포인트 영속화 — embedding_backfill_jobs(V26) 매핑.
 * [이유] EmbeddingBackfillService가 AtomicBoolean 단일 플래그만 유지하면 재시작 시 진행률 유실(백필 2h+).
 *       V25 ContentBackfillJob(disclosure 도메인) 패턴을 analysis 도메인에 적용.
 *       last_processed_id: 커서(id ASC)의 재개 하한. 재시작 후 running=false ∧ DB status=RUNNING → stale → 재개.
 * [사이드 임팩트] 잡 행은 영구 보존(감사). targeted는 시작 시점 스냅샷 — 진행 중 추가 공시 미반영(정상).
 *               processed+failed가 targeted를 초과할 수 있음(커서 재개 중 신규 content_text 유입 시).
 * [수정 시 고려사항] status/CHECK 제약과 Status enum 정합 필수(V26 불변 — 컬럼 추가는 새 V{n}).
 *                  다중 인스턴스(Kubernetes) 도입 시 stale 판정 로직이 정상 RUNNING 잡을 오인 가능.
 */
@Entity
@Table(name = "embedding_backfill_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class EmbeddingBackfillJob {

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

    public static EmbeddingBackfillJob create() {
        OffsetDateTime now = OffsetDateTime.now();
        return EmbeddingBackfillJob.builder()
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
