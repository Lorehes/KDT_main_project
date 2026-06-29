package com.dartcommons.disclosure;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.entities.ContentBackfillJob;
import com.dartcommons.disclosure.repositories.ContentBackfillJobRepository;
import com.dartcommons.disclosure.services.DisclosureContentBackfillService;
import com.dartcommons.infrastructure.dart.DartDocumentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;

import static com.dartcommons.disclosure.entities.ContentBackfillJob.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] ContentBackfillJob DB 영속화 경로 검증 — 청크 진행률 커밋과 stale RUNNING 잡 탐지.
 *       실제 PostgreSQL(Testcontainers + V25 마이그레이션)에서 ContentBackfillJobStateService의
 *       REQUIRES_NEW 트랜잭션이 별도 빈을 통해 올바르게 커밋되는지 확인.
 * [이유] Mock DB 금지(CLAUDE.md §6-6): processed/lastProcessedId 컬럼 타입·제약을 실 DB로 검증.
 *       V25 마이그레이션 적용 여부도 Flyway 부팅 시 자동 검증 — 스키마 회귀 방어.
 *       ContentBackfillJobStateService가 별도 빈으로 분리되어 self-invocation 없이 REQUIRES_NEW 정상 적용.
 * [사이드 임팩트] 테스트가 공유 content_backfill_jobs 테이블에 데이터를 삽입 — @BeforeEach에서 정리.
 *               DartDocumentClient·DisclosurePollingJob은 MockitoBean으로 외부 호출 차단.
 * [수정 시 고려사항] runAsync()의 @Async 완료 대기는 추가 동기화(CompletableFuture/CountDownLatch)가 필요.
 *                  현재 테스트는 위임 메서드(startJob/recordProgress 등)를 직접 호출해 비동기 의존성 없이 검증.
 *                  createAndStartAsync() 전체 경로(executor.execute → doBackfill)는 DisclosureContentBackfillServiceTest에서 커버.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
})
class ContentBackfillJobIT {

    @MockitoBean private DartDocumentClient dartDocumentClient;
    @MockitoBean private DisclosurePollingJob pollingJob;

    @Autowired private DisclosureContentBackfillService backfillService;
    @Autowired private ContentBackfillJobRepository jobRepository;

    @BeforeEach
    void cleanUp() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("청크 진행률 — 두 번의 recordProgress가 누적되고 succeedJob 후 SUCCEEDED 확인")
    void chunkProgressPersistence_twoChunksSucceeded() {
        ContentBackfillJob job = backfillService.createJob();
        UUID jobId = job.getJobId();

        backfillService.startJob(jobId, 10);
        backfillService.recordProgress(jobId, 5, 0, 50L);   // 1번째 청크
        backfillService.recordProgress(jobId, 4, 1, 100L);  // 2번째 청크 (1개 실패 포함)
        backfillService.succeedJob(jobId);

        ContentBackfillJob result = backfillService.findByJobId(jobId).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(SUCCEEDED);
        assertThat(result.getProcessed()).isEqualTo(9);          // 5 + 4
        assertThat(result.getFailed()).isEqualTo(1);
        assertThat(result.getLastProcessedId()).isEqualTo(100L);
        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getFinishedAt()).isNotNull();
        assertThat(result.getTargeted()).isEqualTo(10);
    }

    @Test
    @DisplayName("stale RUNNING 탐지 — findFirstByStatusOrderByCreatedAtDesc가 RUNNING 잡과 lastProcessedId 반환")
    void staleRunningDetection_repositoryReturnsLatestRunningJob() {
        // 크래시 시뮬레이션: DB에 RUNNING 잡 직접 삽입 (service 없이 엔티티 메서드로)
        ContentBackfillJob stale = jobRepository.save(ContentBackfillJob.create());
        stale.start(100);
        stale.recordChunkProgress(30, 0, 300L);
        jobRepository.save(stale);

        Optional<ContentBackfillJob> found =
                jobRepository.findFirstByStatusOrderByCreatedAtDesc(RUNNING);

        assertThat(found).isPresent();
        assertThat(found.get().getJobId()).isEqualTo(stale.getJobId());
        assertThat(found.get().getLastProcessedId()).isEqualTo(300L);
        assertThat(found.get().getProcessed()).isEqualTo(30);
    }

    @Test
    @DisplayName("stale RUNNING 마킹 — failJob 후 FAILED 전환 + errorMessage 기록")
    void failJob_marksStaleJobAsFailed() {
        ContentBackfillJob stale = jobRepository.save(ContentBackfillJob.create());
        stale.start(50);
        stale.recordChunkProgress(10, 0, 100L);
        jobRepository.save(stale);

        backfillService.failJob(stale.getJobId(), "Superseded by new job on restart");

        ContentBackfillJob updated = jobRepository.findByJobId(stale.getJobId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(FAILED);
        assertThat(updated.getErrorMessage()).contains("Superseded");
        assertThat(updated.getFinishedAt()).isNotNull();
        // lastProcessedId는 failJob 후에도 유지 — 재개 포인트로 활용
        assertThat(updated.getLastProcessedId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("createJob — PENDING 상태로 저장되고 jobId UUID가 할당된다")
    void createJob_persistsAsPending() {
        ContentBackfillJob job = backfillService.createJob();

        assertThat(job.getJobId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(PENDING);
        assertThat(job.getProcessed()).isEqualTo(0);
        assertThat(job.getTargeted()).isEqualTo(0);

        // DB에서 재조회해 실제 영속화 확인
        ContentBackfillJob fromDb = jobRepository.findByJobId(job.getJobId()).orElseThrow();
        assertThat(fromDb.getStatus()).isEqualTo(PENDING);
    }
}
