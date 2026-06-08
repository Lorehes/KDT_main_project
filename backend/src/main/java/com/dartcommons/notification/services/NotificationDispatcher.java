package com.dartcommons.notification.services;

import com.dartcommons.analysis.entities.AnalysisResult.Sentiment;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.shared.event.AnalysisCompletedEvent;
import com.dartcommons.user.entities.PortfolioEntity;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.PortfolioRepository;
import com.dartcommons.user.repositories.UserRepository;
import com.dartcommons.shared.util.TradingHoursUtil;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/*
 * [목적] AnalysisCompletedEvent(AFTER_COMMIT) 구독 → 보유 종목 역조회 → 4단계 INSTANT 필터 → 채널 발송 → 이력 기록.
 *       알림 발송 실패가 분석 TX에 전파되지 않도록 AFTER_COMMIT + @Async("notificationExecutor") 격리.
 * [이유] feature_structure §2: analysis→notification 결합도 해소는 shared 이벤트 경유.
 *       발송 예외를 user 단위 try-catch로 격리해 1명 실패가 전체 발송을 막지 않도록.
 * [사이드 임팩트] disclosure·user 도메인 직접 참조 — MVP 한시 허용. Sentiment·Disclosure 공유 이관 후 제거 대상.
 *               PortfolioRepository.findByStockCode 대량 조회 가능 — 추후 페이지네이션 고려.
 *               TELEGRAM은 MVP 미지원으로 FAILED 기록 후 종료.
 * [수정 시 고려사항] notifyFrequency != INSTANT 사용자는 DigestDispatchJob(Wave 3+) 담당 — 여기선 skip.
 *                  dedup 위반(DataIntegrityViolationException)은 catch-skip 패턴 — 별도 TX 롤백이므로 caller TX 무결.
 *                  KAKAO 발송 시 전화번호 복호화 실패(AesGcmEncryptor.CryptoException)는 FAILED 기록.
 */
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final DisclosureRepository       disclosureRepository;
    private final PortfolioRepository        portfolioRepository;
    private final UserRepository             userRepository;
    private final NotificationRepository     notificationRepository;
    private final NotificationMessageBuilder messageBuilder;
    private final KakaoAlimtalkClient        kakaoAlimtalkClient;
    private final MailNotificationClient     mailNotificationClient;
    private final AesGcmEncryptor            aesGcmEncryptor;

    /**
     * LLM 분석 완료 이벤트 수신 — 분석 TX commit 이후 별도 스레드에서 실행.
     * withheld=true(판단 보류)면 즉시 반환.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Async("notificationExecutor")
    public void onAnalysisCompleted(AnalysisCompletedEvent event) {
        if (event.withheld()) {
            log.debug("Analysis {} withheld — skipping dispatch", event.analysisId());
            return;
        }

        Optional<Disclosure> disclosureOpt = disclosureRepository.findById(event.disclosureId());
        if (disclosureOpt.isEmpty()) {
            log.warn("Disclosure {} not found — dispatch skipped for analysis {}", event.disclosureId(), event.analysisId());
            return;
        }
        Disclosure disclosure = disclosureOpt.get();
        if (disclosure.getStockCode() == null) {
            log.debug("Disclosure {} has no stock_code — dispatch skipped", event.disclosureId());
            return;
        }

        List<PortfolioEntity> portfolios = portfolioRepository.findByStockCode(disclosure.getStockCode());
        if (portfolios.isEmpty()) return;

        Set<Long> dispatched = new HashSet<>();
        for (PortfolioEntity portfolio : portfolios) {
            Long userId = portfolio.getUserId();
            if (!dispatched.add(userId)) continue;
            try {
                dispatchForUser(userId, disclosure, event.sentiment(), event.confidence());
            } catch (Exception e) {
                log.error("Notification dispatch error for user {}, disclosure {}: {}",
                        userId, event.disclosureId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 단일 사용자에 대한 4단계 필터 + 채널 발송.
     * 각 notificationRepository.save()는 SimpleJpaRepository의 자체 TX로 처리됨.
     */
    private void dispatchForUser(Long userId, Disclosure disclosure, Sentiment sentiment, BigDecimal confidence) {
        Optional<UserEntity> userOpt = userRepository.findByIdAndDeletedAtIsNull(userId);
        if (userOpt.isEmpty()) return;
        UserEntity user = userOpt.get();

        // 1단계: 알림 비활성화
        if (!user.isNotifyEnabled()) return;

        // 2단계: 호재/악재 유형 필터
        if (!matchesTypeFilter(user.getNotifyTypeFilter(), sentiment)) return;

        // 3단계: 거래시간 외 차단
        if (!user.isOffHoursAllowed() && !TradingHoursUtil.isWithinTradingHoursNow()) return;

        // 4단계: INSTANT 빈도만 즉시 발송 (DAILY_*/WEEKLY는 DigestDispatchJob 담당)
        if (user.getNotifyFrequency() != UserEntity.NotifyFrequency.INSTANT) return;

        NotificationEntity.Channel channel = NotificationEntity.Channel.valueOf(user.getNotifyChannel().name());
        NotificationEntity record = NotificationEntity.builder()
                .userId(userId)
                .disclosureId(disclosure.getId())
                .channel(channel)
                .build();

        // dedup INSERT — uq_notification_dedup 위반 시 skip
        try {
            record = notificationRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate notification skip — user {}, disclosure {}, channel {}", userId, disclosure.getId(), channel);
            return;
        }

        String body    = messageBuilder.buildBody(disclosure, sentiment, confidence);
        String subject = messageBuilder.buildSubject(disclosure, sentiment);

        try {
            switch (channel) {
                case KAKAO    -> sendKakao(user, body, record);
                case EMAIL    -> sendEmail(user, subject, body, record);
                case TELEGRAM -> markUnsupported(record, "TELEGRAM not supported in MVP");
            }
        } catch (Exception e) {
            record.markFailed(e.getMessage());
            notificationRepository.save(record);
            log.error("Send failed — user {}, channel {}: {}", userId, channel, e.getMessage());
        }
    }

    private void sendKakao(UserEntity user, String body, NotificationEntity record) {
        String phoneNumber;
        try {
            phoneNumber = aesGcmEncryptor.decrypt(user.getPhoneNumberEnc());
        } catch (Exception e) {
            record.markFailed("Phone number decryption failed");
            notificationRepository.save(record);
            log.warn("Phone decrypt failed for user {} — notification FAILED", user.getId());
            return;
        }
        if (phoneNumber == null || phoneNumber.isBlank()) {
            record.markFailed("Phone number not registered");
            notificationRepository.save(record);
            return;
        }
        kakaoAlimtalkClient.send(phoneNumber, body);
        record.markSent();
        notificationRepository.save(record);
    }

    private void sendEmail(UserEntity user, String subject, String body, NotificationEntity record) {
        mailNotificationClient.send(user.getEmail(), subject, body);
        record.markSent();
        notificationRepository.save(record);
    }

    private void markUnsupported(NotificationEntity record, String reason) {
        record.markFailed(reason);
        notificationRepository.save(record);
    }

    private static boolean matchesTypeFilter(UserEntity.NotifyTypeFilter filter, Sentiment sentiment) {
        return switch (filter) {
            case POSITIVE_ONLY -> sentiment == Sentiment.POSITIVE;
            case NEGATIVE_ONLY -> sentiment == Sentiment.NEGATIVE;
            case ALL           -> true;
        };
    }
}
