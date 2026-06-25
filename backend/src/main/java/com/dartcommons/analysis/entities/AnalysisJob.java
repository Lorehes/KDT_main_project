package com.dartcommons.analysis.entities;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.lang.NonNull;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] LLM 분석 배치 백필 잡의 상태/진행률 추적 — analysis_jobs(V13) 매핑.
 * [이유] disclosure backfill_jobs(V12)는 from_date/to_date 의미라 시맨틱 불일치.
 *       analysis 백필은 "미분석 공시 id 범위 + 단계" 의미라 분리.
 * [사이드 임팩트] 잡 행은 영구 보존(감사). 진행률은 청크 1회 완료 시 UPDATE — 비용 낮음.
 *               엔티티는 analysis 도메인에 위치 — 분석 백필이 analysis 책임이므로 자연스러움.
 * [수정 시 고려사항] status/stage CHECK 제약과 정합 유지. 컬럼 추가는 새 V{n} 마이그레이션.
 *                  stage는 본 Spec=2, Stage 3~5는 후속 Spec에서 재사용 가능.
 */
@Entity
@Table(name = "analysis_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class AnalysisJob {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "stage", nullable = false)
    private short stage;

    /** NULL이면 전체 미분석 공시 대상. */
    @Column(name = "disclosure_id_from")
    private Long disclosureIdFrom;

    @Column(name = "disclosure_id_to")
    private Long disclosureIdTo;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "chunks_total")
    private Integer chunksTotal;

    @Column(name = "chunks_done", nullable = false)
    private int chunksDone;

    @Column(name = "targeted", nullable = false)
    private int targeted;

    @Column(name = "analyzed", nullable = false)
    private int analyzed;

    @Column(name = "failed", nullable = false)
    private int failed;

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

    public static @NonNull AnalysisJob create(short stage, Long idFrom, Long idTo, int chunkSize) {
        OffsetDateTime now = OffsetDateTime.now();
        return AnalysisJob.builder()
                .jobId(UUID.randomUUID())
                .status(Status.PENDING)
                .stage(stage)
                .disclosureIdFrom(idFrom)
                .disclosureIdTo(idTo)
                .chunkSize(chunkSize)
                .chunksDone(0)
                .targeted(0)
                .analyzed(0)
                .failed(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void start(int chunksTotal, int targeted) {
        this.status = Status.RUNNING;
        this.chunksTotal = chunksTotal;
        this.targeted = targeted;
        this.startedAt = OffsetDateTime.now();
        this.updatedAt = this.startedAt;
    }

    /** 청크 1개 완료 — 누적 갱신. */
    public void recordChunkProgress(int analyzedDelta, int failedDelta) {
        this.chunksDone += 1;
        this.analyzed += analyzedDelta;
        this.failed += failedDelta;
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
