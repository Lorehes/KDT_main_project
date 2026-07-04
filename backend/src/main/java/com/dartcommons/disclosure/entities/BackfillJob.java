package com.dartcommons.disclosure.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] 비동기 백필 잡의 상태/진행률 추적 — backfill_jobs(V12) 매핑.
 * [이유] 동기 백필은 시간 단위 → HTTP 타임아웃 위험. jobId를 즉시 반환하고 진행률 DB 영속화.
 * [사이드 임팩트] 잡 행은 영구 보존(감사). 정리 정책은 운영자 수동/별도 cleanup 잡.
 *               엔티티는 disclosure 도메인에 위치 — 백필이 disclosure 책임이므로 자연스러움.
 * [수정 시 고려사항] status 변경은 DB CHECK 제약과 정합 유지. 컬럼 추가는 새 V{n} 마이그레이션.
 *                  진행률 빈번 갱신(청크당 1회) — UPDATE 비용 낮음(인덱스 영향 무).
 */
@Entity
@Table(name = "backfill_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class BackfillJob {

    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false, unique = true)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private Status status;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "emit_events", nullable = false)
    private boolean emitEvents;

    @Column(name = "chunks_total")
    private Integer chunksTotal;

    @Column(name = "chunks_done", nullable = false)
    private int chunksDone;

    @Column(name = "fetched", nullable = false)
    private int fetched;

    @Column(name = "saved", nullable = false)
    private int saved;

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

    public static BackfillJob create(LocalDate from, LocalDate to, boolean emitEvents) {
        OffsetDateTime now = OffsetDateTime.now();
        return BackfillJob.builder()
                .jobId(UUID.randomUUID())
                .status(Status.PENDING)
                .fromDate(from)
                .toDate(to)
                .emitEvents(emitEvents)
                .chunksDone(0)
                .fetched(0)
                .saved(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** RUNNING 전이 — startedAt 기록. */
    public void start(int chunksTotal) {
        this.status = Status.RUNNING;
        this.chunksTotal = chunksTotal;
        this.startedAt = OffsetDateTime.now();
        this.updatedAt = this.startedAt;
    }

    /** 청크 1개 완료 시 호출 — 누적 갱신. */
    public void recordChunkProgress(int fetched, int saved) {
        this.chunksDone += 1;
        this.fetched += fetched;
        this.saved += saved;
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
