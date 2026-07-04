package com.dartcommons.disclosure;

import com.dartcommons.disclosure.entities.ContentBackfillJob;
import com.dartcommons.disclosure.repositories.ContentBackfillJobRepository;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.disclosure.services.ContentBackfillJobStateService;
import com.dartcommons.disclosure.services.DisclosureContentBackfillService;
import com.dartcommons.disclosure.services.DisclosureContentService;
import com.dartcommons.infrastructure.dart.DartApiProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/*
 * [목적] DisclosureContentBackfillService 커서 워터마크 루프·CAS 중복 방지 핵심 시나리오 단위 검증.
 * [이유] 93k 백필 실행 전 cursor 전진·safety cap·AtomicBoolean CAS를 런타임 외에서 검증.
 *       DB 영속화(REQUIRES_NEW 격리)는 ContentBackfillJobIT(Testcontainers)에서 별도 커버.
 * [사이드 임팩트] @Async 어노테이션은 Spring 컨텍스트 없이 처리되지 않음 — runAsync()가 동기 실행됨.
 *               ContentBackfillJobStateService는 Mock — 상태 전이 DB 반영 없이 루프 로직만 검증.
 *               throttleMs=0으로 sleep 분기 미진입.
 * [수정 시 고려사항] chunkSize=3(소규모)으로 다중 청크 경로 빠르게 검증.
 *                  LENIENT 모드: BeforeEach 공통 stub이 테스트별 실행 경로에 따라 선택적으로 사용됨.
 *                  createAndStartAsync()는 executor.execute()를 호출 — 단위 테스트에서 mock executor 필요.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DisclosureContentBackfillServiceTest {

    @Mock private ContentBackfillJobStateService stateService;
    @Mock private ContentBackfillJobRepository jobRepository;
    @Mock private DisclosureRepository disclosureRepository;
    @Mock private DisclosureContentService disclosureContentService;
    @Mock private DartApiProperties props;
    @Mock private TaskExecutor contentFetchExecutor;

    private DisclosureContentBackfillService service;
    private UUID testJobId;
    private ContentBackfillJob testJob;

    @BeforeEach
    void setUp() {
        testJobId = UUID.randomUUID();
        testJob = ContentBackfillJob.create();
        service = new DisclosureContentBackfillService(
                stateService, jobRepository, disclosureRepository, disclosureContentService, props, contentFetchExecutor);

        when(props.contentBackfillChunkSize()).thenReturn(3);
        when(props.contentBackfillThrottleMs()).thenReturn(0L);
        when(jobRepository.findByJobId(any())).thenReturn(Optional.of(testJob));
        when(jobRepository.findFirstByStatusOrderByCreatedAtDesc(any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("2청크 후 빈 배열 → 커서 올바르게 전진하고 총 5건 처리")
    void runAsync_twoChunks_cursorsAdvanceAndAllIdsProcessed() {
        when(disclosureRepository.countPendingContentFetch()).thenReturn(5L);
        when(disclosureRepository.findPendingContentFetchIds(null, PageRequest.of(0, 3)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(disclosureRepository.findPendingContentFetchIds(3L, PageRequest.of(0, 3)))
                .thenReturn(List.of(4L, 5L));
        when(disclosureRepository.findPendingContentFetchIds(5L, PageRequest.of(0, 3)))
                .thenReturn(List.of());

        service.runAsync(testJobId);

        verify(disclosureContentService, times(5)).fetchAndSave(any());
        verify(disclosureRepository, times(3)).findPendingContentFetchIds(any(), any());
        // stateService.recordProgress가 청크마다 호출됨 (2회 — 비어있는 3번째 청크는 break)
        verify(stateService, times(2)).recordProgress(any(), anyInt(), anyInt(), any());
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("estimated=0 → safetyCap=4 → 첫 청크에서 break, fetchAndSave 미호출")
    void runAsync_estimatedZero_immediateBreakNoFetchCalled() {
        when(disclosureRepository.countPendingContentFetch()).thenReturn(0L);
        when(disclosureRepository.findPendingContentFetchIds(null, PageRequest.of(0, 3)))
                .thenReturn(List.of());

        service.runAsync(testJobId);

        verify(disclosureContentService, never()).fetchAndSave(any());
        verify(disclosureRepository, times(1)).findPendingContentFetchIds(any(), any());
        verify(stateService).succeedJob(testJobId);
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("동시 호출 두번째는 CAS 실패로 즉시 반환 — countPendingContentFetch 1회만 호출")
    void runAsync_concurrentCall_secondSkippedByCas() throws InterruptedException {
        CountDownLatch t1InFind = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(disclosureRepository.countPendingContentFetch()).thenReturn(0L);
        when(disclosureRepository.findPendingContentFetchIds(any(), any())).thenAnswer(inv -> {
            t1InFind.countDown();
            releaseFirst.await(500, TimeUnit.MILLISECONDS);
            return List.of();
        });

        UUID secondJobId = UUID.randomUUID();
        Thread t1 = new Thread(() -> service.runAsync(testJobId));
        t1.start();
        t1InFind.await(1000, TimeUnit.MILLISECONDS);

        service.runAsync(secondJobId);  // CAS 실패 → 즉시 반환

        releaseFirst.countDown();
        t1.join(1000);

        verify(disclosureRepository, times(1)).countPendingContentFetch();
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("createAndStartAsync — CAS 성공 시 executor.execute 호출 + Optional<job> 반환")
    void createAndStartAsync_casSuccess_executorSubmitted() {
        when(jobRepository.save(any())).thenReturn(testJob);

        Optional<ContentBackfillJob> result = service.createAndStartAsync();

        assertThat(result).isPresent();
        // executor.execute()가 호출되어 doBackfill을 비동기 제출
        verify(contentFetchExecutor).execute(any());
    }

    @Test
    @DisplayName("createAndStartAsync — 이미 실행 중이면 Optional.empty 반환, executor 미호출")
    void createAndStartAsync_alreadyRunning_returnsEmpty() {
        // 첫 번째 호출로 running=true 만들기
        when(jobRepository.save(any())).thenReturn(testJob);
        service.createAndStartAsync();  // CAS 성공 → running=true (executor.execute는 stub이므로 실행 안 됨)

        // 두 번째 호출
        Optional<ContentBackfillJob> second = service.createAndStartAsync();

        assertThat(second).isEmpty();
        verify(contentFetchExecutor, times(1)).execute(any()); // 첫 번째만 호출됨
    }
}
