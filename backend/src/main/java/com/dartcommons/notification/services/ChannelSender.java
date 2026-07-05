package com.dartcommons.notification.services;

import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.infrastructure.telegram.TelegramClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.shared.event.TelegramBotBlockedEvent;
import com.dartcommons.user.entities.UserEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/*
 * [목적] 채널별 알림 발송 로직을 단일 진실 소스(Single Source of Truth)로 집중.
 *       NotificationDispatcher(최초 발송)와 NotificationRetryJob(재발송) 모두 이 빈을 주입해 동일 경로 사용.
 * [이유] Tech Review 설계 결정 B: Dispatcher·RetryJob이 각자 발송 로직을 가지면 문구·오류처리 불일치 위험.
 *       ChannelSender 추출로 중복 제거 + 채널 추가 시 이 파일만 수정하면 됨(OCP).
 * [사이드 임팩트] Dispatcher·RetryJob 모두 이 빈에 의존 — ChannelSender 장애 시 양쪽 발송 모두 중단.
 *               record.markSent()/markFailed()·save()를 내부에서 처리하므로 호출자는 결과만 확인.
 *               TELEGRAM 봇 차단(403) 감지 시 TelegramBotBlockedEvent 발행 — chat_id 해제는 user 도메인
 *               리스너(TelegramLinkService.onBotBlocked)가 수행(§3-2 도메인 write 직접 침범 금지).
 *               영구 실패(전화번호/chat_id 미등록, 봇 차단)는 markFailed 종결 → RetryJob 재시도 대상 아님.
 * [수정 시 고려사항] 채널 추가 시 switch case + 클라이언트 주입만 추가 — 호출자 변경 불필요.
 *                  다중 인스턴스 배포 시 분산 락(ShedLock) 적용은 RetryJob 레이어에서 담당.
 *                  카카오 알림톡 templateCode 가변화 필요 시 NotificationEntity에 templateCode 컬럼 추가 검토.
 *                  텔레그램 일시 실패(타임아웃·5xx·429)는 TelegramClient @Retryable 소진 후 throw
 *                  → Dispatcher가 PENDING 유지 → RetryJob 픽업(기존 재시도 의미론 그대로).
 */
@Component
@RequiredArgsConstructor
public class ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(ChannelSender.class);

    private final KakaoAlimtalkClient       kakaoAlimtalkClient;
    private final MailNotificationClient    mailNotificationClient;
    private final TelegramClient            telegramClient;
    private final NotificationRepository    notificationRepository;
    private final AesGcmEncryptor           aesGcmEncryptor;
    private final ApplicationEventPublisher eventPublisher;

    /**
     * 채널에 따라 발송 후 record 상태 갱신·저장.
     * 발송 성공: SENT + sent_at=now(). 영구 실패: FAILED + error_message(재시도 제외).
     * 일시 실패: 예외 전파 → 호출자(Dispatcher)가 PENDING 유지 → RetryJob 재발송.
     */
    public void send(UserEntity user, NotificationEntity record) {
        switch (record.getChannel()) {
            case KAKAO    -> sendKakao(user, record);
            case EMAIL    -> sendEmail(user, record);
            case TELEGRAM -> sendTelegram(user, record);
        }
    }

    private void sendKakao(UserEntity user, NotificationEntity record) {
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
        // dev mode(senderKey=placeholder) 시 send()가 실 API 미호출 후 true 반환 → markSent() 기록됨.
        // notification_logs에 SENT로 남지만 실제 전달 없음 — 개발 환경 한정, 운영 전 카카오 비즈채널 등록 필수.
        kakaoAlimtalkClient.send(phoneNumber, record.getMessageBody());
        record.markSent();
        notificationRepository.save(record);
    }

    private void sendEmail(UserEntity user, NotificationEntity record) {
        mailNotificationClient.send(user.getEmail(), record.getMessageSubject(), record.getMessageBody());
        record.markSent();
        notificationRepository.save(record);
    }

    /**
     * 텔레그램 발송. chat_id 미연동 → FAILED(TELEGRAM_NOT_LINKED, 재시도 무의미).
     * 봇 차단(403) → chat_id 해제 + FAILED(TELEGRAM_BLOCKED_BY_USER) — 이후 발송 시도 원천 차단.
     */
    private void sendTelegram(UserEntity user, NotificationEntity record) {
        String chatId = user.getTelegramChatId();
        if (chatId == null || chatId.isBlank()) {
            record.markFailed("TELEGRAM_NOT_LINKED");
            notificationRepository.save(record);
            return;
        }
        try {
            telegramClient.send(chatId, record.getMessageBody());
        } catch (TelegramClient.TelegramForbiddenException e) {
            eventPublisher.publishEvent(new TelegramBotBlockedEvent(user.getId()));
            record.markFailed("TELEGRAM_BLOCKED_BY_USER");
            notificationRepository.save(record);
            log.warn("Telegram bot blocked by user {} — unlink event published, notification FAILED", user.getId());
            return;
        }
        record.markSent();
        notificationRepository.save(record);
    }
}
