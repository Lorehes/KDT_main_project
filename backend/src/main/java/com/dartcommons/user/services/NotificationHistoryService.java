package com.dartcommons.user.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.user.dto.NotificationResponse;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/*
 * [목적] 알림 이력 조회(GET /api/v1/notifications) + 읽음 처리(PATCH) + 미읽음 카운트(GET) + 테스트 발송(POST) 서비스.
 * [이유] 알림 이력은 Disclosure·AnalysisResult를 bulk 조인해야 FE Notification 타입의 corp_name·sentiment 제공 가능.
 *       테스트 발송은 NotificationEntity(disclosure_id NOT NULL) 없이 채널 인프라를 직접 호출 — 설정 검증 용도.
 *       읽음 처리는 IDOR 방어를 위해 userId 소유권 검증 후 markRead() 또는 bulk UPDATE 수행.
 * [사이드 임팩트] 테스트 발송은 DB에 이력을 남기지 않음 — 알림 이력에 표시되지 않음.
 *               cross-domain 의존(disclosure·analysis·notification) — MVP 한시 허용. 추후 공유 인터페이스 분리 가능.
 *               markRead: 단건 알림 조회 후 userId 일치 확인 → markRead() 호출 → save. 불일치 시 403.
 *               markAllRead: bulk UPDATE로 N+1 없이 처리. 반환 값(updated count)은 서비스 내부 사용.
 * [수정 시 고려사항] 알림 이력에 페이지네이션 추가 시 NotificationRepository에 Pageable 메서드 추가.
 *                  테스트 발송 성공/실패 응답을 상세화하려면 TestNotificationResult 레코드 반환으로 변경.
 *                  getUnreadCount 캐싱(Caffeine) 추가 시 invalidate 전략: markRead/markAllRead 호출 시 evict.
 */
@Service
@RequiredArgsConstructor
public class NotificationHistoryService {

    private final NotificationRepository   notificationRepository;
    private final DisclosureRepository     disclosureRepository;
    private final AnalysisResultRepository analysisResultRepository;
    private final UserRepository           userRepository;
    private final KakaoAlimtalkClient      kakaoAlimtalkClient;
    private final MailNotificationClient   mailNotificationClient;
    private final AesGcmEncryptor          aesGcmEncryptor;

    private static final String TEST_MESSAGE =
            "[DartCommons 알림 테스트]\n설정이 완료되었습니다.\n※ 본 내용은 투자 권유가 아닌 정보 제공용입니다.";

    /** 단건 읽음 처리. userId 소유권 검증(IDOR 방어) 후 markRead() 호출. 이미 읽은 알림은 DB write 생략. */
    @Transactional
    public void markRead(Long userId, Long notificationId) {
        NotificationEntity notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다."));
        if (!notification.getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "접근 권한이 없습니다.");
        }
        if (!notification.isRead()) {
            notification.markRead();
            notificationRepository.save(notification);
        }
    }

    /** 전체 읽음 처리 — bulk UPDATE로 N+1 없이 처리. */
    @Transactional
    public void markAllRead(Long userId) {
        notificationRepository.markAllReadByUserId(userId, java.time.OffsetDateTime.now());
    }

    /** 미읽음 알림 수 조회 — TopBar 벨 뱃지용. */
    @Transactional(readOnly = true)
    public long getUnreadCount(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }

    @Transactional(readOnly = true)
    public List<NotificationResponse> list(Long userId) {
        List<NotificationEntity> notifications = notificationRepository.findByUserId(userId);
        if (notifications.isEmpty()) return List.of();

        // bulk 조회 — N+1 방지
        List<Long> disclosureIds = notifications.stream()
                .map(NotificationEntity::getDisclosureId).distinct().toList();
        Map<Long, Disclosure> disclosureMap = disclosureRepository.findAllById(disclosureIds)
                .stream().collect(Collectors.toMap(Disclosure::getId, d -> d));
        Map<Long, AnalysisResult> analysisMap = analysisResultRepository
                .findByDisclosureIdIn(disclosureIds)
                .stream().collect(Collectors.toMap(AnalysisResult::getDisclosureId, ar -> ar));

        return notifications.stream()
                .sorted((a, b) -> {
                    if (a.getCreatedAt() == null || b.getCreatedAt() == null) return 0;
                    return b.getCreatedAt().compareTo(a.getCreatedAt());
                })
                .map(n -> {
                    Disclosure    disclosure = disclosureMap.get(n.getDisclosureId());
                    AnalysisResult ar        = disclosure != null
                            ? analysisMap.get(disclosure.getId()) : null;
                    Sentiment sentiment      = ar != null ? ar.getSentiment() : null;
                    return NotificationResponse.from(n, disclosure, sentiment);
                })
                .toList();
    }

    /** 설정 검증용 테스트 발송 — DB 이력 없이 채널 인프라 직접 호출. */
    public void sendTest(Long userId) {
        UserEntity user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

        switch (user.getNotifyChannel()) {
            case EMAIL    -> mailNotificationClient.send(user.getEmail(), "[DartCommons] 알림 테스트", TEST_MESSAGE);
            case KAKAO    -> sendTestKakao(user);
            case TELEGRAM -> throw new ResponseStatusException(
                    HttpStatus.UNPROCESSABLE_ENTITY, "텔레그램 채널은 MVP에서 미지원입니다.");
        }
    }

    private void sendTestKakao(UserEntity user) {
        if (user.getPhoneNumberEnc() == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "카카오 알림톡 발송을 위해 휴대폰 번호를 먼저 등록해주세요.");
        }
        String phone;
        try {
            phone = aesGcmEncryptor.decrypt(user.getPhoneNumberEnc());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "휴대폰 번호 복호화에 실패했습니다.");
        }
        kakaoAlimtalkClient.send(phone, TEST_MESSAGE);
    }
}
