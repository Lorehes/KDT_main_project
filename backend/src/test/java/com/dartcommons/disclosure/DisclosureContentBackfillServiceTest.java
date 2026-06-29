package com.dartcommons.disclosure;

import com.dartcommons.disclosure.repositories.DisclosureRepository;
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
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/*
 * [목적] DisclosureContentBackfillService 커서 워터마크 루프의 핵심 시나리오 단위 검증.
 * [이유] 93k 백필 실행 전 cursor 전진·safety cap·AtomicBoolean CAS 중복 방지를 런타임 외에서 검증
 *       (dc-review-code MEDIUM 이슈 — content-fetch-backfill-pagination Spec 리뷰 결과).
 * [사이드 임팩트] @Async 어노테이션은 Spring 컨텍스트 없이 처리되지 않음 — 동기 실행으로 테스트.
 *               throttleMs=0 설정으로 sleep 분기 미진입. interrupt 경로는 별도 스레드 분기로 검증.
 * [수정 시 고려사항] chunkSize=3(소규모)으로 다중 청크 경로를 빠르게 검증.
 *                  @SpringBootTest + Testcontainers가 필요한 쿼리 정확성은 DisclosureContentServiceIT에 위임.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // setUp stub이 테스트별 실행 경로에 따라 선택적으로 사용됨
class DisclosureContentBackfillServiceTest {

    @Mock
    private DisclosureRepository disclosureRepository;

    @Mock
    private DisclosureContentService disclosureContentService;

    @Mock
    private DartApiProperties props;

    private DisclosureContentBackfillService service;

    @BeforeEach
    void setUp() {
        service = new DisclosureContentBackfillService(disclosureRepository, disclosureContentService, props);
        when(props.contentBackfillChunkSize()).thenReturn(3);    // 소규모 청크로 다중 청크 경로 빠르게 검증
        when(props.contentBackfillThrottleMs()).thenReturn(0L);  // sleep 비활성화
    }

    @Test
    @DisplayName("2청크 후 빈 배열 → 커서 올바르게 전진하고 총 5건 처리")
    void runBackfill_twoChunks_cursorsAdvanceAndAllIdsProcessed() {
        // given: 첫 청크 3건(lastId=null→3), 두번째 청크 2건(lastId=3→5), 세번째 빈 배열 → break
        when(disclosureRepository.countPendingContentFetch()).thenReturn(5L);
        when(disclosureRepository.findPendingContentFetchIds(null, PageRequest.of(0, 3)))
                .thenReturn(List.of(1L, 2L, 3L));
        when(disclosureRepository.findPendingContentFetchIds(3L, PageRequest.of(0, 3)))
                .thenReturn(List.of(4L, 5L));
        when(disclosureRepository.findPendingContentFetchIds(5L, PageRequest.of(0, 3)))
                .thenReturn(List.of());

        // when
        service.runBackfill();

        // then
        verify(disclosureContentService, times(5)).fetchAndSave(any());
        verify(disclosureRepository, times(3)).findPendingContentFetchIds(any(), any());
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("estimated=0 → safetyCap=4 → 첫 청크에서 break, fetchAndSave 미호출")
    void runBackfill_estimatedZero_immediateBreakNoFetchCalled() {
        // given: estimated=0 → safetyCap=(0/3+2)*2=4, 첫 조회 즉시 빈 배열
        when(disclosureRepository.countPendingContentFetch()).thenReturn(0L);
        when(disclosureRepository.findPendingContentFetchIds(null, PageRequest.of(0, 3)))
                .thenReturn(List.of());

        // when
        service.runBackfill();

        // then
        verify(disclosureContentService, never()).fetchAndSave(any());
        verify(disclosureRepository, times(1)).findPendingContentFetchIds(any(), any());
        assertThat(service.isRunning()).isFalse();
    }

    @Test
    @DisplayName("동시 호출 두번째는 CAS 실패로 즉시 반환 — countPendingContentFetch 1회만 호출")
    void runBackfill_concurrentCall_secondSkippedByCas() throws InterruptedException {
        // given: 첫 호출이 find 중 블록될 때 두번째 호출 시도
        CountDownLatch t1InFind = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        when(disclosureRepository.countPendingContentFetch()).thenReturn(0L);
        when(disclosureRepository.findPendingContentFetchIds(any(), any())).thenAnswer(inv -> {
            t1InFind.countDown();                            // 첫 호출이 find에 진입했음을 알림
            releaseFirst.await(500, TimeUnit.MILLISECONDS);  // 두번째 호출 완료 대기
            return List.of();
        });

        Thread t1 = new Thread(() -> service.runBackfill());
        t1.start();
        t1InFind.await(1000, TimeUnit.MILLISECONDS); // t1이 find 진입 후

        // when: 두번째 호출 — running=true이므로 CAS 실패 → 즉시 반환
        service.runBackfill();

        releaseFirst.countDown();
        t1.join(1000);

        // then: count는 첫 호출에서만 1번, running은 최종 false
        verify(disclosureRepository, times(1)).countPendingContentFetch();
        assertThat(service.isRunning()).isFalse();
    }
}
