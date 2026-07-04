package com.dartcommons.notification;

import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.notification.services.NotificationRetryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/*
 * [목적] 5분 주기 배치 잡 — PENDING/RETRYING 고착 알림 레코드를 재발송해 사용자 미수신 최소화.
 *       PENDING 고착 시나리오: Dispatcher 일시적 오류(카카오 API 타임아웃 등) 후 record를 PENDING으로 유지 → 재발송 대상.
 * [이유] Spec notification-retry-job: PENDING/RETRYING 레코드를 최대 MAX_RETRY=3회 재발송.
 *       Dispatcher는 일시적 오류 시 FAILED를 기록하지 않고 PENDING 유지 → 이 잡이 재발송 경로 제공.
 * [사이드 임팩트] NotificationRetryService.retryOne()을 통해 단건 처리 — @Transactional 프록시 정상 작동.
 *               건별 try-catch로 1건 실패가 다음 건 재발송 중단하지 않음.
 *               배치 크기 BATCH_SIZE=100 — 외부 API 장애 복구 후 대량 누적 시 OOM 방지.
 *               다중 인스턴스 배포 시 markAsRetrying CAS + ShedLock 추가 권장 (MVP: 단일 인스턴스 허용).
 * [수정 시 고려사항] BATCH_SIZE·MAX_RETRY는 application.yml(dartcommons.notification.*)로 외부화 권장.
 *                  채널 실패율 모니터링 추가 시 MeterRegistry(Micrometer) 주입해 counter 증분.
 *                  ShedLock 적용 시 @SchedulerLock(name="notificationRetryJob", ...) 추가.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dartcommons.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationRetryJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryJob.class);

    private static final int BATCH_SIZE = 100;

    private final NotificationRepository  notificationRepository;
    private final NotificationRetryService retryService;

    @Scheduled(fixedDelay = 300_000, initialDelay = 60_000)
    public void retryFailedNotifications() {
        List<NotificationEntity> targets = notificationRepository.findRetryTargets(
                NotificationRetryService.RETRY_STATUSES,
                NotificationRetryService.MAX_RETRY,
                PageRequest.of(0, BATCH_SIZE)
        );

        if (targets.isEmpty()) {
            log.debug("RetryJob: no retry targets found");
            return;
        }

        log.info("RetryJob: {} target(s) found (batch limit={})", targets.size(), BATCH_SIZE);
        int successCount = 0;
        int failCount    = 0;

        for (NotificationEntity record : targets) {
            try {
                boolean processed = retryService.retryOne(record);
                if (processed) successCount++; else failCount++;
            } catch (Exception e) {
                // 건별 격리 — 예외가 다음 레코드 재발송 중단하지 않음
                failCount++;
                log.error("RetryJob: unexpected error for notification id={}: {}",
                        record.getId(), e.getMessage(), e);
            }
        }

        log.info("RetryJob: done — attempted={}, skipped={}", successCount, failCount);
    }
}
