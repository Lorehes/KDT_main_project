package com.dartcommons.notification;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Channel;
import com.dartcommons.notification.entities.NotificationEntity.Status;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.notification.services.NotificationRetryService;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/*
 * [목적] NotificationRetryJob·NotificationRetryService 통합 테스트 — Spec 카드 #7.
 *       Testcontainers PostgreSQL 실 DB 사용(Mock DB 금지 — CLAUDE.md §6-6).
 *       실제 V15 마이그레이션 적용 환경에서 재발송 상태머신 전 경로 검증.
 * [이유] RetryJob 핵심 기능(PENDING→SENT, 3회 소진→FAILED, pre-V15 skip) + findRetryTargets 쿼리 정합성
 *       은 Testcontainers 실 DB에서만 검증 가능.
 * [사이드 임팩트] @BeforeEach에서 notifications·users·disclosures 테이블 초기화.
 *               DisclosurePollingJob·KakaoAlimtalkClient·MailNotificationClient MockitoBean 대체.
 * [수정 시 고려사항] 테스트에서 NotificationEntity 상태를 직접 설정(builder)하므로 entity 필드 추가 시 픽스처 갱신.
 *                  KAKAO 채널 테스트는 AesGcmEncryptor + 전화번호 암호화 필요 — 추후 별도 테스트 케이스 추가.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "dartcommons.dart.api-key=test-key",
        "dartcommons.dart.base-url=http://localhost",
        "dartcommons.krx.api-key=test-key",
        "dartcommons.krx.base-url=http://localhost",
        "dartcommons.admin.username=admin",
        "dartcommons.admin.password=test-admin-password",
        "dartcommons.llm.provider=mock"
        // scheduling.enabled 미설정 → matchIfMissing=true → RetryJob 빈 생성
        // initialDelay=60s 덕분에 테스트 실행 중 @Scheduled 자동 발화 없음
})
class NotificationRetryJobIntegrationTest {

    @MockitoBean DisclosurePollingJob   pollingJob;
    @MockitoBean KakaoAlimtalkClient    kakaoAlimtalkClient;
    @MockitoBean MailNotificationClient mailNotificationClient;

    @Autowired NotificationRetryJob     retryJob;
    @Autowired NotificationRetryService retryService;
    @Autowired NotificationRepository   notificationRepository;
    @Autowired UserRepository           userRepository;
    @Autowired DisclosureRepository     disclosureRepository;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        disclosureRepository.deleteAll();
        userRepository.deleteAll();
        reset(kakaoAlimtalkClient, mailNotificationClient);
    }

    // ===== 1. 골든 패스 — PENDING → SENT =====

    @Test
    @DisplayName("PENDING 레코드 + 메일 발송 성공 → status=SENT, sent_at 설정")
    void pendingRecord_mailSendSucceeds_becomesSent() {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();
        NotificationEntity record = saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.PENDING, 0,
                "테스트 본문 내용", "테스트 이메일 제목");

        retryService.retryOne(record);

        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Status.SENT);
        assertThat(updated.getSentAt()).isNotNull();
        verify(mailNotificationClient).send(eq(user.getEmail()), eq("테스트 이메일 제목"), eq("테스트 본문 내용"));
    }

    // ===== 2. RETRYING 레코드도 재발송 대상 =====

    @Test
    @DisplayName("RETRYING 레코드 + 메일 발송 성공 → status=SENT")
    void retryingRecord_mailSendSucceeds_becomesSent() {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();
        NotificationEntity record = saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.RETRYING, 1,
                "본문", "제목");

        retryService.retryOne(record);

        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Status.SENT);
        assertThat(updated.getSentAt()).isNotNull();
    }

    // ===== 3. 재시도 실패 — retryCount < MAX_RETRY 이면 RETRYING 유지 =====

    @Test
    @DisplayName("재발송 실패 + retryCount < MAX_RETRY → status=RETRYING 유지 (다음 배치 재시도 가능)")
    void retryFails_retryCountBelowMax_statusRemainsRetrying() {
        when(mailNotificationClient.send(any(), any(), any()))
                .thenThrow(new RuntimeException("SMTP connection refused"));

        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();
        NotificationEntity record = saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.PENDING, 0,
                "본문", "제목");

        retryService.retryOne(record);

        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        // markAsRetrying: PENDING → RETRYING + retryCount 0→1
        // channelSender.send() throw → retryCount 1 < 3 → RETRYING 유지
        assertThat(updated.getStatus()).isEqualTo(Status.RETRYING);
        assertThat(updated.getRetryCount()).isEqualTo(1);
        assertThat(updated.getSentAt()).isNull();
    }

    // ===== 4. 3회 재시도 소진 → FAILED 확정 =====

    @Test
    @DisplayName("retryCount=MAX_RETRY-1 레코드 + 재발송 실패 → FAILED 확정 (status=FAILED, retryCount=MAX_RETRY)")
    void retryExhausted_retryCountReachesMax_becomesFailed() {
        when(mailNotificationClient.send(any(), any(), any()))
                .thenThrow(new RuntimeException("Kakao API 500"));

        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();
        // retryCount = MAX_RETRY - 1 = 2; markAsRetrying 후 → 3 = MAX_RETRY → FAILED
        NotificationEntity record = saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.RETRYING,
                NotificationRetryService.MAX_RETRY - 1, "본문", "제목");

        retryService.retryOne(record);

        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Status.FAILED);
        assertThat(updated.getRetryCount()).isEqualTo(NotificationRetryService.MAX_RETRY);
        assertThat(updated.getErrorMessage()).contains("Max retry exceeded");
    }

    // ===== 5. pre-V15 레코드 (messageBody=null) → FAILED 기록, 발송 없음 =====

    @Test
    @DisplayName("V15 이전 레코드(messageBody=null) → FAILED 기록, 채널 클라이언트 미호출")
    void preV15Record_nullMessageBody_becomesFailedWithoutSend() {
        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();
        NotificationEntity record = saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.PENDING, 0,
                null, null); // V15 이전: body/subject NULL

        retryService.retryOne(record);

        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Status.FAILED);
        assertThat(updated.getErrorMessage()).contains("pre-V15");
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 6. 이미 SENT (markAsRetrying CAS skip) =====

    @Test
    @DisplayName("sent_at이 이미 설정된 레코드 → markAsRetrying 조건 불충족 → skip (재발송 없음)")
    void alreadySentRecord_markAsRetryingSkips_noResend() {
        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();

        // 직접 SENT 레코드 생성: sent_at IS NOT NULL → markAsRetrying의 WHERE 조건 불충족 → 0건 반환
        NotificationEntity record = NotificationEntity.builder()
                .userId(user.getId())
                .disclosureId(disclosure.getId())
                .channel(Channel.EMAIL)
                .status(Status.SENT)
                .retryCount(0)
                .messageBody("body")
                .messageSubject("subject")
                .build();
        record.markSent(); // sent_at = now()
        record = notificationRepository.save(record);

        boolean processed = retryService.retryOne(record);

        assertThat(processed).isFalse(); // skip
        // status 변경 없음
        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Status.SENT);
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 7. retryJob.retryFailedNotifications() 일괄 처리 =====

    @Test
    @DisplayName("retryJob 직접 호출 — PENDING 3건 발견 → 전건 SENT 처리")
    void retryJob_batchProcesses_allPendingRecordsBecomesSent() {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user = createEmailUser();
        // dedup(user_id, disclosure_id, channel) 충돌 없이 3건 저장 — disclosure 각각 생성
        Disclosure d1 = createDisclosure(), d2 = createDisclosure(), d3 = createDisclosure();
        saveRecord(user.getId(), d1.getId(), Channel.EMAIL, Status.PENDING, 0, "본문1", "제목1");
        saveRecord(user.getId(), d2.getId(), Channel.EMAIL, Status.PENDING, 0, "본문2", "제목2");
        saveRecord(user.getId(), d3.getId(), Channel.EMAIL, Status.PENDING, 0, "본문3", "제목3");

        retryJob.retryFailedNotifications();

        List<NotificationEntity> all = notificationRepository.findByUserId(user.getId());
        assertThat(all).hasSize(3);
        assertThat(all).allMatch(n -> n.getStatus() == Status.SENT);
        verify(mailNotificationClient, times(3)).send(any(), any(), any());
    }

    // ===== 8. findRetryTargets — retryCount >= MAX_RETRY 레코드 제외 =====

    @Test
    @DisplayName("findRetryTargets: retryCount=MAX_RETRY 레코드는 조회 대상 제외")
    void findRetryTargets_excludesMaxRetryReached() {
        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();

        // retryCount = MAX_RETRY → 재시도 대상 아님
        saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.RETRYING,
                NotificationRetryService.MAX_RETRY, "본문", "제목");

        List<NotificationEntity> targets = notificationRepository.findRetryTargets(
                NotificationRetryService.RETRY_STATUSES,
                NotificationRetryService.MAX_RETRY,
                PageRequest.of(0, 100)
        );

        assertThat(targets).isEmpty();
    }

    // ===== 9. findRetryTargets — FAILED 레코드 제외 =====

    @Test
    @DisplayName("findRetryTargets: status=FAILED 레코드는 조회 대상 제외")
    void findRetryTargets_excludesFailedStatus() {
        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();

        saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.FAILED, 0, "본문", "제목");

        List<NotificationEntity> targets = notificationRepository.findRetryTargets(
                NotificationRetryService.RETRY_STATUSES,
                NotificationRetryService.MAX_RETRY,
                PageRequest.of(0, 100)
        );

        assertThat(targets).isEmpty();
    }

    // ===== 10. 삭제된 사용자 → FAILED 기록 =====

    @Test
    @DisplayName("사용자 soft-delete(deletedAt 설정) 상태 → retryOne → FAILED 기록")
    void deletedUser_retryOne_becomesFailedWithUserNotFound() {
        UserEntity user       = createEmailUser();
        Disclosure disclosure = createDisclosure();
        NotificationEntity record = saveRecord(user.getId(), disclosure.getId(), Channel.EMAIL, Status.PENDING, 0,
                "본문", "제목");

        // 사용자 soft-delete
        user.softDelete();
        userRepository.save(user);

        retryService.retryOne(record);

        NotificationEntity updated = notificationRepository.findById(record.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(Status.FAILED);
        assertThat(updated.getErrorMessage()).contains("User not found");
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== fixture helpers =====

    private UserEntity createEmailUser() {
        OffsetDateTime now = OffsetDateTime.now();
        return userRepository.save(UserEntity.builder()
                .email("retry-test-" + UUID.randomUUID().toString().substring(0, 8) + "@test.com")
                .nickname("재시도테스터")
                .notifyChannel(UserEntity.NotifyChannel.EMAIL)
                .notifyEnabled(true)
                .notifyFrequency(UserEntity.NotifyFrequency.INSTANT)
                .notifyTypeFilter(UserEntity.NotifyTypeFilter.ALL)
                .offHoursAllowed(true)
                .termsAgreedAt(now)
                .privacyAgreedAt(now)
                .build());
    }

    private Disclosure createDisclosure() {
        return disclosureRepository.save(Disclosure.builder()
                .rceptNo(UUID.randomUUID().toString().replace("-", "").substring(0, 14))
                .corpCode("00000000")
                .stockCode("005930")
                .corpName("테스트회사")
                .reportNm("재시도 테스트 공시")
                .rceptDt(LocalDate.of(2026, 6, 8))
                .disclosureType("TREASURY_STOCK")
                .build());
    }

    /**
     * 특정 상태·retryCount로 NotificationEntity를 저장한다.
     * storeMessage()는 record 저장 전 빌더로 직접 설정한다.
     */
    private NotificationEntity saveRecord(Long userId, Long disclosureId, Channel channel,
                                          Status status, int retryCount,
                                          String messageBody, String messageSubject) {
        NotificationEntity entity = NotificationEntity.builder()
                .userId(userId)
                .disclosureId(disclosureId)
                .channel(channel)
                .status(status)
                .retryCount(retryCount)
                .messageBody(messageBody)
                .messageSubject(messageSubject)
                .build();
        return notificationRepository.save(entity);
    }
}
