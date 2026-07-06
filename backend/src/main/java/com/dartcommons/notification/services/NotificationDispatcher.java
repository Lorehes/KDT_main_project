package com.dartcommons.notification.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.repositories.NotificationRepository;
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
 * [사이드 임팩트] disclosure·analysis·user 도메인 직접 참조 — MVP 한시 허용. Sentiment·Disclosure 공유 이관 후 제거 대상.
 *               PortfolioRepository.findByStockCode 대량 조회 가능 — 추후 페이지네이션 고려.
 *               본문은 채널별로 여기서 확정되어 message_body 저장 — TELEGRAM은 HTML 본문(+3줄 요약 1회 조회).
 *               채널 발송은 ChannelSender로 위임 — Dispatcher 직접 발송 로직 제거.
 * [수정 시 고려사항] notifyFrequency != INSTANT 사용자는 DigestDispatchJob(Wave 3+) 담당 — 여기선 skip.
 *                  dedup 위반(DataIntegrityViolationException)은 catch-skip 패턴 — 별도 TX 롤백이므로 caller TX 무결.
 *                  발송 실패 시 ChannelSender가 FAILED 기록 — Dispatcher는 예외 catch 후 로그만.
 */
@Component
@RequiredArgsConstructor
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final DisclosureRepository       disclosureRepository;
    private final AnalysisResultRepository   analysisResultRepository;
    private final PortfolioRepository        portfolioRepository;
    private final UserRepository             userRepository;
    private final NotificationRepository     notificationRepository;
    private final NotificationMessageBuilder messageBuilder;
    private final ChannelSender              channelSender;

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

        // 텔레그램 본문에만 3줄 요약 포함(Spec R6) — 이벤트 페이로드에 없어 분석 결과에서 1회 조회
        String summary = analysisResultRepository.findById(event.analysisId())
                .map(AnalysisResult::getSummary).orElse(null);

        Set<Long> dispatched = new HashSet<>();
        for (PortfolioEntity portfolio : portfolios) {
            Long userId = portfolio.getUserId();
            if (!dispatched.add(userId)) continue;
            try {
                dispatchForUser(userId, disclosure, event.sentiment(), event.confidence(), summary);
            } catch (Exception e) {
                log.error("Notification dispatch error for user {}, disclosure {}: {}",
                        userId, event.disclosureId(), e.getMessage(), e);
            }
        }
    }

    /** 관리자 수동 발송 — 분석 결과가 있는 공시에 대해 즉시 발송 트리거. */
    public void dispatchForDisclosure(Long disclosureId) {
        AnalysisResult ar = analysisResultRepository.findByDisclosureId(disclosureId)
                .orElseThrow(() -> new IllegalArgumentException("No analysis result for disclosure " + disclosureId));
        if (ar.isWithheld()) throw new IllegalArgumentException("Analysis withheld for disclosure " + disclosureId);

        Disclosure disclosure = disclosureRepository.findById(disclosureId)
                .orElseThrow(() -> new IllegalArgumentException("Disclosure not found: " + disclosureId));
        if (disclosure.getStockCode() == null) throw new IllegalArgumentException("Disclosure has no stock_code");

        List<PortfolioEntity> portfolios = portfolioRepository.findByStockCode(disclosure.getStockCode());
        String summary = ar.getSummary();
        Set<Long> dispatched = new HashSet<>();
        for (PortfolioEntity portfolio : portfolios) {
            Long userId = portfolio.getUserId();
            if (!dispatched.add(userId)) continue;
            try {
                dispatchForUser(userId, disclosure, ar.getSentiment(), ar.getConfidence(), summary);
            } catch (Exception e) {
                log.error("Manual dispatch error for user {}, disclosure {}: {}", userId, disclosureId, e.getMessage());
            }
        }
        log.info("Manual dispatch done: disclosureId={} portfolioUsers={}", disclosureId, dispatched.size());
    }

    /**
     * 단일 사용자에 대한 4단계 필터 + 채널 발송.
     * record 생성 시 body/subject 저장 → RetryJob 재발송 시 재조회 불필요.
     */
    private void dispatchForUser(Long userId, Disclosure disclosure, Sentiment sentiment,
                                 BigDecimal confidence, String summary) {
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

        // 본문은 여기서 채널별로 확정되어 message_body에 저장 — RetryJob이 그대로 재발송(Tech Review §1)
        String body = user.getNotifyChannel() == UserEntity.NotifyChannel.TELEGRAM
                ? messageBuilder.buildTelegramBody(disclosure, sentiment, confidence, summary)
                : messageBuilder.buildBody(disclosure, sentiment, confidence);
        String subject = messageBuilder.buildSubject(disclosure, sentiment);

        NotificationEntity.Channel channel = NotificationEntity.Channel.valueOf(user.getNotifyChannel().name());
        NotificationEntity record = NotificationEntity.builder()
                .userId(userId)
                .disclosureId(disclosure.getId())
                .channel(channel)
                .build();
        record.storeMessage(body, subject);

        // dedup INSERT — uq_notification_dedup 위반 시 skip
        try {
            record = notificationRepository.save(record);
        } catch (DataIntegrityViolationException e) {
            log.debug("Duplicate notification skip — user {}, disclosure {}, channel {}", userId, disclosure.getId(), channel);
            return;
        }

        try {
            channelSender.send(user, record);
        } catch (Exception e) {
            // 일시적 채널 오류(타임아웃, 서버 불응답 등) — record는 PENDING 상태로 유지됨.
            // NotificationRetryJob이 5분 이내에 PENDING 레코드를 감지해 재발송.
            // 영구 실패(전화번호 없음, TELEGRAM 미지원)는 ChannelSender 내부에서 markFailed() + save() 처리됨.
            log.warn("Send attempt failed for user={}, channel={} — leaving PENDING for RetryJob: {}",
                    userId, channel, e.getClass().getSimpleName());
        }
    }

    private static boolean matchesTypeFilter(UserEntity.NotifyTypeFilter filter, Sentiment sentiment) {
        return switch (filter) {
            case POSITIVE_ONLY -> sentiment == Sentiment.POSITIVE;
            case NEGATIVE_ONLY -> sentiment == Sentiment.NEGATIVE;
            case ALL           -> true;
        };
    }
}
