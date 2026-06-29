package com.dartcommons.disclosure.services;

import com.dartcommons.infrastructure.dart.DartApiProperties;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * [목적] content_fetched_at IS NULL인 공시를 일괄 순회하며 본문 fetch 백필을 수행.
 *       93k 기적재 공시의 content_text를 채우는 1회성(또는 수동 트리거) 작업.
 * [이유] 신규 공시는 DisclosureContentFetchListener(이벤트 기반)가 처리하지만,
 *       기적재 공시(Stage 1 수집 완료분)는 이벤트가 없으므로 별도 백필 필요.
 *       throttle(기본 500ms): DART 일일 호출 한도 보호. 한도 실측 전 보수적 설정.
 *       contentFetchExecutor(max=2)와 throttle의 이중 제어로 DART API 과부하 방지.
 * [사이드 임팩트] 93k건 × 500ms = 최소 12.7시간 — 운영 시간대 외 실행 권장(현재 수동 트리거).
 *               진행 중 서버 재시작 시 content_fetched_at IS NULL인 공시부터 재시작됨(멱등 보장).
 *               contentFetchExecutor 큐(300) 초과 시 TaskRejectedException — 큐 크기 범위 내 호출 필수.
 * [수정 시 고려사항] 93k 완료 후 throttle 지연을 낮출 수 있음(한도 측정 기반).
 *                  자동 스케줄(야간 배치)이 필요하면 @Scheduled + @Async 조합으로 SchedulingConfig에 추가.
 *                  배치 진행률 모니터링: 로그 외 별도 상태 테이블(BackfillJob 패턴)으로 확장 가능(content-fetch-backfill-resilience Spec).
 *                  AtomicBoolean running: JVM 단일 인스턴스 중복 실행 방지. 다중 인스턴스(Kubernetes) 환경에서는
 *                  DB 레벨 분산 락(예: PostgreSQL advisory lock)으로 교체 필요.
 */
@Service
public class DisclosureContentBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DisclosureContentBackfillService.class);

    // HIGH-6 fix: JVM 단일 인스턴스 동시 실행 방지 — compareAndSet으로 중복 runBackfill() 차단
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
     * content_fetched_at IS NULL인 공시 전체를 최신순으로 순회하며 본문을 fetch한다.
     * contentFetchExecutor 스레드에서 @Async 실행 — 호출자(컨트롤러 등)는 즉시 반환됨.
     * 동시 실행 방지: AtomicBoolean CAS — 이미 실행 중이면 warn 로그 후 즉시 반환.
     */
    @Async("contentFetchExecutor")
    public void runBackfill() {
        if (!running.compareAndSet(false, true)) {
            log.warn("DisclosureContentBackfillService: already running, skip duplicate call");
            return;
        }
        try {
            List<Long> pendingIds = disclosureRepository.findPendingContentFetchIds();
            int total = pendingIds.size();
            log.info("DisclosureContentBackfillService: start total={}", total);

            int processed = 0;
            for (Long id : pendingIds) {
                disclosureContentService.fetchAndSave(id);
                processed++;

                if (processed % 100 == 0) {
                    log.info("DisclosureContentBackfillService: progress {}/{}", processed, total);
                }

                if (props.contentBackfillThrottleMs() > 0) {
                    try {
                        Thread.sleep(props.contentBackfillThrottleMs());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("DisclosureContentBackfillService: interrupted at {}/{}", processed, total);
                        return;
                    }
                }
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
