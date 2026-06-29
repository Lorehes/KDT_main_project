package com.dartcommons.disclosure.services;

import com.dartcommons.infrastructure.dart.DartApiProperties;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * [목적] content_fetched_at IS NULL인 공시를 커서 기반 청크로 순회하며 본문 fetch 백필을 수행.
 *       93k 기적재 공시의 content_text를 채우는 1회성(또는 수동 트리거) 작업.
 * [이유] content-fetch-backfill-pagination Spec: 이전 구현은 전체 ID를 in-memory List로 로드(93k× 힙 점유).
 *       AnalysisBackfillService의 검증된 워터마크 커서 패턴 채택:
 *       countPendingContentFetch() → safety cap 산출 → findPendingContentFetchIds(lastId, chunkSize) 반복.
 *       커서 전진으로 DB 부하 분산 + 백필 중 추가된 pending 공시 자동 포함.
 *       throttle(기본 500ms) + contentFetchExecutor(max=2)의 이중 제어로 DART API 과부하 방지.
 * [사이드 임팩트] ORDER BY 변경: 이전 rcept_dt DESC(최신 우선) → id ASC(등록 순).
 *               운영상 처리 완결성이 최신 우선보다 중요하므로 허용(Tech Review 결정).
 *               transient 실패(RestClientException) 시 content_fetched_at=null 유지 → 같은 run 내 재시도 안 됨
 *               (워터마크가 lastId > id로 전진). 이전 in-memory 방식과 동일 동작 — 회귀 아님.
 *               다음 백필 트리거 시 재처리됨.
 *               AtomicBoolean running: JVM 단일 인스턴스 중복 실행 방지. 다중 인스턴스(Kubernetes) 환경에서는
 *               content-fetch-backfill-resilience Spec의 분산 락으로 교체 필요.
 * [수정 시 고려사항] contentBackfillChunkSize: DART 일일 호출 한도 실측 후 조정 가능(현재 100 보수적).
 *                  safety cap: (estimated/chunkSize + 2) * 2 — 0건일 때 cap=4로 방어. estimated가
 *                  완료 중 감소하므로 실제 청크 수와 오차 있음(정상).
 *                  진행률 DB 영속화(재시작 복구)는 content-fetch-backfill-resilience Spec 후속.
 *                  자동 스케줄(야간 배치) 필요 시 @Scheduled + @Async 조합으로 SchedulingConfig에 추가.
 */
@Service
public class DisclosureContentBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DisclosureContentBackfillService.class);

    // JVM 단일 인스턴스 동시 실행 방지 — compareAndSet으로 중복 runBackfill() 차단
    private final AtomicBoolean running = new AtomicBoolean(false);

    private final DisclosureRepository disclosureRepository;
    private final DisclosureContentService disclosureContentService;
    private final DartApiProperties props;

    public DisclosureContentBackfillService(DisclosureRepository disclosureRepository,
                                             DisclosureContentService disclosureContentService,
                                             DartApiProperties props) {
        this.disclosureRepository = disclosureRepository;
        this.disclosureContentService = disclosureContentService;
        this.props = props;
    }

    /**
     * content_fetched_at IS NULL인 공시 전체를 커서 기반 청크로 순회하며 본문을 fetch한다.
     *
     * <p>커서 패턴: {@code lastId=null}에서 시작, 청크 완료 후 {@code lastId=ids.getLast()}(Java 21)로 전진.
     * <p>safety cap: {@code (initialEstimated/chunkSize+2)*2} — 무한루프 방지(청크 크기보다 예상 청크 수의 2배 상한).
     * <p>동시 실행 방지: AtomicBoolean CAS — 이미 실행 중이면 warn 로그 후 즉시 반환.
     * <p>contentFetchExecutor 스레드에서 @Async 실행 — 호출자(컨트롤러 등)는 즉시 반환됨.
     */
    @Async("contentFetchExecutor")
    public void runBackfill() {
        if (!running.compareAndSet(false, true)) {
            log.warn("DisclosureContentBackfillService: already running, skip duplicate call");
            return;
        }
        try {
            long initialEstimated = disclosureRepository.countPendingContentFetch();
            int chunkSize = props.contentBackfillChunkSize();
            // safety cap: 예상 청크 수 × 2. initialEstimated=0이면 cap=4(방어 최솟값).
            int safetyCap = (int) ((initialEstimated / Math.max(chunkSize, 1) + 2) * 2);
            log.info("DisclosureContentBackfillService: start initialEstimated={} chunkSize={} safetyCap={}",
                    initialEstimated, chunkSize, safetyCap);

            Long lastId = null;
            int processed = 0;

            for (int i = 0; i < safetyCap; i++) {
                List<Long> ids = disclosureRepository.findPendingContentFetchIds(
                        lastId, PageRequest.of(0, chunkSize));
                if (ids.isEmpty()) break;

                for (Long id : ids) {
                    disclosureContentService.fetchAndSave(id);
                    processed++;

                    if (props.contentBackfillThrottleMs() > 0) {
                        try {
                            Thread.sleep(props.contentBackfillThrottleMs());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("DisclosureContentBackfillService: interrupted at {}/{} lastId={}",
                                    processed, initialEstimated, lastId);
                            return;
                        }
                    }
                }

                // 워터마크 전진 — 실패 여부와 무관하게 전진해야 무한루프 없음
                lastId = ids.getLast();
                log.info("DisclosureContentBackfillService: chunk done processed={}/{} lastId={}",
                        processed, initialEstimated, lastId);
            }

            log.info("DisclosureContentBackfillService: done processed={}", processed);
        } finally {
            running.set(false);
        }
    }

    /** 현재 백필 실행 중 여부 반환 — 관리자 엔드포인트 상태 조회용. */
    public boolean isRunning() {
        return running.get();
    }
}
