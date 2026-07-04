package com.dartcommons.analysis;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.entities.EmbeddingBackfillJob;
import com.dartcommons.analysis.repositories.EmbeddingBackfillJobRepository;
import com.dartcommons.analysis.services.EmbeddingBackfillService;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.infrastructure.dart.DartDocumentClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.dartcommons.analysis.entities.EmbeddingBackfillJob.Status.*;
import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] EmbeddingBackfillJob DB 영속화 경로 검증 — 청크 진행률 커밋과 stale RUNNING 잡 탐지.
 *       실제 PostgreSQL(Testcontainers + V26 마이그레이션)에서 EmbeddingBackfillJobStateService의
 *       REQUIRES_NEW 트랜잭션이 별도 빈을 통해 올바르게 커밋되는지 확인.
 * [이유] Mock DB 금지(CLAUDE.md §6-6): processed/lastProcessedId 컬럼 타입·제약을 실 DB로 검증.
 *       V26 마이그레이션 적용 여부도 Flyway 부팅 시 자동 검증 — 스키마 회귀 방어.
 * [사이드 임팩트] 테스트가 공유 embedding_backfill_jobs 테이블에 데이터 삽입 — @BeforeEach에서 정리.
 *               DartDocumentClient·DisclosurePollingJob은 MockitoBean으로 외부 호출 차단.
 * [수정 시 고려사항] createAndStartAsync() 전체 경로의 비동기 실행(doBackfill)은 여기서 미검증.
 *                  위임 메서드(startJob/recordProgress 등)를 직접 호출해 비동기 의존성 없이 DB 경로 검증.
 *                  doBackfill 통합은 별도 슬로우 테스트로 추가 가능(MockEmbeddingClient + await).
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
class EmbeddingBackfillJobIT {

    @MockitoBean private DartDocumentClient dartDocumentClient;
    @MockitoBean private DisclosurePollingJob pollingJob;

    @Autowired private EmbeddingBackfillService backfillService;
    @Autowired private EmbeddingBackfillJobRepository jobRepository;

    @BeforeEach
    void cleanUp() {
        jobRepository.deleteAll();
    }

    @Test
    @DisplayName("잡 생성 → PENDING 상태로 저장됨")
    void createJob_returnsPending() {
        EmbeddingBackfillJob job = backfillService.createJob();

        assertThat(job.getJobId()).isNotNull();
        assertThat(job.getStatus()).isEqualTo(PENDING);
        assertThat(job.getProcessed()).isZero();
        assertThat(job.getTargeted()).isZero();
    }

    @Test
    @DisplayName("startJob → RUNNING + targeted 갱신")
    void startJob_setsRunning() {
        EmbeddingBackfillJob job = backfillService.createJob();
        backfillService.startJob(job.getJobId(), 67847);

        EmbeddingBackfillJob updated = jobRepository.findByJobId(job.getJobId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(RUNNING);
        assertThat(updated.getTargeted()).isEqualTo(67847);
        assertThat(updated.getStartedAt()).isNotNull();
    }

    @Test
    @DisplayName("청크 2회 recordProgress → processed 누적 + lastProcessedId 전진")
    void recordProgress_accumulatesAndAdvancesCursor() {
        EmbeddingBackfillJob job = backfillService.createJob();
        UUID jobId = job.getJobId();
        backfillService.startJob(jobId, 200);

        backfillService.recordProgress(jobId, 100, 0, 100L);
        backfillService.recordProgress(jobId, 95, 5, 200L);

        EmbeddingBackfillJob updated = jobRepository.findByJobId(jobId).orElseThrow();
        assertThat(updated.getProcessed()).isEqualTo(195);
        assertThat(updated.getFailed()).isEqualTo(5);
        assertThat(updated.getLastProcessedId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("stale RUNNING 잡 탐지 — findFirstByStatusOrderByCreatedAtDesc 정상 동작")
    void staleRunningJob_detectedByRepository() {
        EmbeddingBackfillJob job = backfillService.createJob();
        backfillService.startJob(job.getJobId(), 100);

        Optional<EmbeddingBackfillJob> stale = jobRepository
                .findFirstByStatusOrderByCreatedAtDesc(RUNNING);

        assertThat(stale).isPresent();
        assertThat(stale.get().getJobId()).isEqualTo(job.getJobId());
    }

    @Test
    @DisplayName("succeedJob → SUCCEEDED + finishedAt 설정")
    void succeedJob_setsSucceeded() {
        EmbeddingBackfillJob job = backfillService.createJob();
        backfillService.startJob(job.getJobId(), 10);
        backfillService.succeedJob(job.getJobId());

        EmbeddingBackfillJob updated = jobRepository.findByJobId(job.getJobId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SUCCEEDED);
        assertThat(updated.getFinishedAt()).isNotNull();
    }

    @Test
    @DisplayName("failJob → FAILED + errorMessage 저장")
    void failJob_setsFailedWithMessage() {
        EmbeddingBackfillJob job = backfillService.createJob();
        backfillService.startJob(job.getJobId(), 10);
        backfillService.failJob(job.getJobId(), "Ollama not running");

        EmbeddingBackfillJob updated = jobRepository.findByJobId(job.getJobId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(FAILED);
        assertThat(updated.getErrorMessage()).contains("Ollama not running");
    }

    @Test
    @DisplayName("CAS 중복 방지 — running=true이면 createAndStartAsync가 empty 반환")
    void createAndStartAsync_returnEmptyIfAlreadyRunning() throws Exception {
        // running 플래그를 강제로 true로 설정해 '이미 실행 중' 상태 재현 → CAS가 두 번째 시작을 막는지 검증.
        AtomicBoolean running = extractRunningFlag();
        running.set(true);
        try {
            assertThat(backfillService.createAndStartAsync()).isEmpty();
        } finally {
            running.set(false);
        }
    }

    private AtomicBoolean extractRunningFlag() throws Exception {
        // @Transactional CGLIB 프록시의 필드는 null — 타깃 객체를 언랩해 실제 running 플래그 접근.
        EmbeddingBackfillService target = AopTestUtils.getTargetObject(backfillService);
        var field = EmbeddingBackfillService.class.getDeclaredField("running");
        field.setAccessible(true);
        return (AtomicBoolean) field.get(target);
    }
}
