package com.dartcommons.notification.services;

import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Status;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/*
 * [목적] NotificationRetryJob의 단건 재발송 트랜잭션 경계를 Spring 프록시로 올바르게 적용하기 위한 분리 서비스.
 *       RetryJob에서 self-invocation 시 @Transactional이 무시되는 Spring AOP 제한을 우회.
 * [이유] Spring AOP는 같은 빈의 내부 메서드 호출에 프록시를 적용하지 않는다.
 *       @Transactional 경계가 필요한 retryOne()을 별도 빈(NotificationRetryService)으로 분리하면
 *       RetryJob → (프록시) → Service 호출이 되어 @Transactional이 정상 작동한다.
 *       (correctness-architecture 리뷰 HIGH 이슈 수정: self-invocation @Transactional bypass)
 * [사이드 임팩트] NotificationRetryJob은 이 빈만 주입하면 됨 — ChannelSender·Repository를 직접 보유하지 않아도 됨.
 *               @Transactional 내에 외부 HTTP 호출(channelSender)이 포함되어 DB 커넥션 점유 시간이 늘어남.
 *               MVP 규모에서는 허용. 향후 개선: send 후 commit, after-commit 훅으로 상태 저장.
 * [수정 시 고려사항] 트랜잭션 격리 수준: 기본 READ_COMMITTED.
 *                  markAsRetrying의 clearAutomatically=true로 캐시 클리어 — findById가 항상 최신값 반환.
 *                  다중 인스턴스 배포 시 ShedLock + markAsRetrying CAS가 유일한 이중 발송 방어선.
 */
@Service
@RequiredArgsConstructor
public class NotificationRetryService {

    private static final Logger log = LoggerFactory.getLogger(NotificationRetryService.class);

    private final NotificationRepository notificationRepository;
    private final UserRepository         userRepository;
    private final ChannelSender          channelSender;

    public static final int          MAX_RETRY      = 3;
    public static final List<Status> RETRY_STATUSES = List.of(Status.PENDING, Status.RETRYING);

    /**
     * 단건 재발송. markAsRetrying CAS로 독점 확보 후 발송.
     * @return true = 발송 시도(성공/실패 무관), false = skip(다른 인스턴스 처리 중 or 이미 SENT)
     */
    @Transactional
    public boolean retryOne(NotificationEntity record) {
        // V15 이전 생성분(messageBody NULL) — CAS 먼저 수행해 독점 확보 후 FAILED 기록
        // CAS를 생략하면 동시 실행 시 동일 레코드를 여러 워커가 FAILED로 덮어쓸 수 있음
        int updated = notificationRepository.markAsRetrying(record.getId(), Status.RETRYING, RETRY_STATUSES, MAX_RETRY);
        if (updated == 0) {
            log.debug("RetryJob: record id={} already claimed or sent — skip", record.getId());
            return false;
        }

        // clearAutomatically=true 덕분에 캐시 클리어 후 최신 DB 값 반환
        NotificationEntity fresh = notificationRepository.findById(record.getId()).orElse(null);
        if (fresh == null) return false;

        // V15 이전 레코드(messageBody NULL): 재발송 불가 → FAILED 확정
        if (fresh.getMessageBody() == null) {
            fresh.markFailed("message_body is null — pre-V15 record, cannot retry");
            notificationRepository.save(fresh);
            log.warn("RetryJob: skip pre-V15 record id={}, marking FAILED", fresh.getId());
            return false;
        }

        Optional<UserEntity> userOpt = userRepository.findByIdAndDeletedAtIsNull(fresh.getUserId());
        if (userOpt.isEmpty()) {
            fresh.markFailed("User not found or deleted");
            notificationRepository.save(fresh);
            log.warn("RetryJob: user {} not found for notification id={}", fresh.getUserId(), fresh.getId());
            return false;
        }

        try {
            channelSender.send(userOpt.get(), fresh);
            log.info("RetryJob: resent notification id={}, channel={}, retryCount={}",
                    fresh.getId(), fresh.getChannel(), fresh.getRetryCount());
            return true;
        } catch (Exception e) {
            // [Spec] 재발송 실패: retryCount >= MAX_RETRY → FAILED 확정, 미만 → RETRYING 유지(DB에 이미 설정됨)
            if (fresh.getRetryCount() >= MAX_RETRY) {
                fresh.markFailed("Max retry exceeded: " + e.getMessage());
                notificationRepository.save(fresh);
            }
            // else: DB 상태는 이미 RETRYING — 다음 배치 사이클에서 재시도됨. save() 불필요.
            log.warn("RetryJob: resend failed id={}, retryCount={}/{}: {}",
                    fresh.getId(), fresh.getRetryCount(), MAX_RETRY, e.getMessage());
            return false;
        }
    }
}
