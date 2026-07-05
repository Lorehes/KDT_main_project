package com.dartcommons.stocks.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * [목적] KRX 과거 주가 백필 잡의 상태·진행률·재개 포인트 영속화 — price_backfill_jobs(V28) 매핑.
 * [이유] 3년 백필은 ~30분 + KRX 불안정 → 재시작 복구 필요. V26 EmbeddingBackfillJob 패턴 적용.
 *       커서가 날짜(lastProcessedDate)인 점만 상이 — 역순(최근→과거) 처리한 가장 오래된 날짜.
 * [사이드 임팩트] 잡 행 영구 보존(감사). targeted=이번 실행 대상 평일 수. processed=데이터 있던 날짜 수(행 수 아님),
 *               failed=빈 응답 날짜 수. 진행률=(processed+failed)/targeted (완주 시 100%). 적재 행 수는 로그로만.
 * [수정 시 고려사항] status/CHECK와 Status enum 정합 필수(V29: CHECK 5종). 다중 인스턴스 시 stale 판정 재검토.
 */
@Entity
@Table(name = "price_backfill_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class PriceBackfillJob {

    /*
     * [목적] 주가 백필 잡의 생명주기 상태 5종 정의.
     * [이유] PARTIAL 추가(V29) — KRX 무료 소스 이력 경계 도달 시 일부 적재 성공했음에도
     *       FAILED로 표기되던 오해를 해소. datesOk>0이면 PARTIAL, ==0이면 FAILED(진짜 장애).
     * [사이드 임팩트] V29 CHECK 제약(5종)과 동기화 필수. PARTIAL 잡의 lastProcessedDate=마지막 성공 날짜(운영 확인용 — 자동 재개 대상 아님).
     * [수정 시 고려사항] 신규 상태 추가 시 V{n} 마이그레이션으로 CHECK 제약 함께 확장.
     */
    public enum Status { PENDING, RUNNING, SUCCEEDED, FAILED, PARTIAL }

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

    /** 역순 백필 재개 포인트 — 이 날짜 이전(< lastProcessedDate)부터 재처리. null이면 처음(최근일)부터. */
    @Column(name = "last_processed_date")
    private LocalDate lastProcessedDate;

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

    public static PriceBackfillJob create() {
        OffsetDateTime now = OffsetDateTime.now();
        return PriceBackfillJob.builder()
                .jobId(UUID.randomUUID())
                .status(Status.PENDING)
                .targeted(0).processed(0).failed(0)
                .createdAt(now).updatedAt(now)
                .build();
    }

    public void start(int targeted) {
        this.status = Status.RUNNING;
        this.targeted = targeted;
        this.startedAt = OffsetDateTime.now();
        this.updatedAt = this.startedAt;
    }

    /** 날짜 처리 완료마다 갱신 — lastProcessedDate 전진(과거로) + 카운터 누적. */
    public void recordProgress(int processedDelta, int failedDelta, LocalDate lastDate) {
        this.processed += processedDelta;
        this.failed += failedDelta;
        this.lastProcessedDate = lastDate;
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

    /*
     * [목적] 연속 빈 응답 안전망 도달 시 일부 적재가 있었으면(datesOk>0) PARTIAL로 종료.
     * [이유] 무료 KRX/GitHub 소스의 이력 경계에 닿으면 받을 건 다 받은 정상 상태인데
     *       기존 throw→FAILED로 "실패"로 오해됐음. PARTIAL = "가용 이력 끝 or 소스 중단, 일부 성공".
     * [사이드 임팩트] lastProcessedDate는 PARTIAL 분기의 recordProgress가 마지막 성공 날짜로 기록 — partial()은 마킹만.
     *               PARTIAL 잡은 자동 재개 대상 아님(stale RUNNING만 재개) — 재실행은 어제부터, upsert 멱등이라 안전.
     * [수정 시 고려사항] reason은 errorMessage에 저장(마스킹 후). succeed()와 동일 구조, status만 PARTIAL.
     */
    public void partial(String reason) {
        this.status = Status.PARTIAL;
        this.errorMessage = reason == null ? null : (reason.length() > 1000 ? reason.substring(0, 1000) : reason);
        this.finishedAt = OffsetDateTime.now();
        this.updatedAt = this.finishedAt;
    }
}
