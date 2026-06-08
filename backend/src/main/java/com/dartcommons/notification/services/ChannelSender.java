package com.dartcommons.notification.services;

import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.user.entities.UserEntity;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/*
 * [목적] 채널별 알림 발송 로직을 단일 진실 소스(Single Source of Truth)로 집중.
 *       NotificationDispatcher(최초 발송)와 NotificationRetryJob(재발송) 모두 이 빈을 주입해 동일 경로 사용.
 * [이유] Tech Review 설계 결정 B: Dispatcher·RetryJob이 각자 발송 로직을 가지면 문구·오류처리 불일치 위험.
 *       ChannelSender 추출로 중복 제거 + 채널 추가 시 이 파일만 수정하면 됨(OCP).
 * [사이드 임팩트] Dispatcher·RetryJob 모두 이 빈에 의존 — ChannelSender 장애 시 양쪽 발송 모두 중단.
 *               record.markSent()/markFailed()·save()를 내부에서 처리하므로 호출자는 결과만 확인.
 *               TELEGRAM은 MVP 미지원 → markUnsupported()로 FAILED 기록.
 * [수정 시 고려사항] 채널 추가 시 switch case + 클라이언트 주입만 추가 — 호출자 변경 불필요.
 *                  다중 인스턴스 배포 시 분산 락(ShedLock) 적용은 RetryJob 레이어에서 담당.
 *                  카카오 알림톡 templateCode 가변화 필요 시 NotificationEntity에 templateCode 컬럼 추가 검토.
 */
@Component
@RequiredArgsConstructor
public class ChannelSender {

    private static final Logger log = LoggerFactory.getLogger(ChannelSender.class);

    private final KakaoAlimtalkClient    kakaoAlimtalkClient;
    private final MailNotificationClient mailNotificationClient;
    private final NotificationRepository notificationRepository;
    private final AesGcmEncryptor        aesGcmEncryptor;

    /**
     * 채널에 따라 발송 후 record 상태 갱신·저장.
     * 발송 성공: SENT + sent_at=now(). 발송 실패: FAILED + error_message.
     * TELEGRAM: MVP 미지원 → FAILED 기록.
     */
    public void send(UserEntity user, NotificationEntity record) {
        switch (record.getChannel()) {
            case KAKAO    -> sendKakao(user, record);
            case EMAIL    -> sendEmail(user, record);
            case TELEGRAM -> markUnsupported(record, "TELEGRAM not supported in MVP");
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
        kakaoAlimtalkClient.send(phoneNumber, record.getMessageBody());
        record.markSent();
        notificationRepository.save(record);
    }

    private void sendEmail(UserEntity user, NotificationEntity record) {
        mailNotificationClient.send(user.getEmail(), record.getMessageSubject(), record.getMessageBody());
        record.markSent();
        notificationRepository.save(record);
    }

    private void markUnsupported(NotificationEntity record, String reason) {
        record.markFailed(reason);
        notificationRepository.save(record);
    }
}
