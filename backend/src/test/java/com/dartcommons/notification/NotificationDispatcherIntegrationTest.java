package com.dartcommons.notification;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.kakao.KakaoAlimtalkClient;
import com.dartcommons.infrastructure.mail.MailNotificationClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Channel;
import com.dartcommons.notification.entities.NotificationEntity.Status;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.shared.crypto.AesGcmEncryptor;
import com.dartcommons.shared.event.AnalysisCompletedEvent;
import com.dartcommons.user.entities.PortfolioEntity;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.entities.UserEntity.NotifyChannel;
import com.dartcommons.user.entities.UserEntity.NotifyFrequency;
import com.dartcommons.user.entities.UserEntity.NotifyTypeFilter;
import com.dartcommons.user.repositories.PortfolioRepository;
import com.dartcommons.user.repositories.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/*
 * [목적] NotificationDispatcher 통합 테스트 — 4단계 INSTANT 필터·채널 라우팅·dedup·withheld 전 경로 검증.
 *       Testcontainers PostgreSQL로 실 DB 검증(Mock DB 금지 — CLAUDE.md §6-6).
 *       KakaoAlimtalkClient·MailNotificationClient는 MockitoBean으로 대체(외부 API 호출 차단).
 * [이유] AFTER_COMMIT + @Async 비동기 발송 경로를 Awaitility로 대기 검증.
 *       notification-dispatcher Spec Wave 3 — notifications 테이블 기록 + dedup 정합성 E2E 보장.
 * [사이드 임팩트] @BeforeEach에서 전 테이블 삭제. 각 테스트는 UUID 이메일 유저로 격리.
 *               DisclosurePollingJob MockitoBean — @Scheduled 폴링 차단.
 * [수정 시 고려사항] 거래시간(off_hours_allowed) 필터 테스트는 TradingHoursUtil에 Clock 주입 도입 후 추가.
 *                  TELEGRAM 채널 테스트는 실 구현 후 Channel.TELEGRAM 유저 픽스처로 추가.
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
})
class NotificationDispatcherIntegrationTest {

    @MockitoBean DisclosurePollingJob   pollingJob;
    @MockitoBean KakaoAlimtalkClient    kakaoAlimtalkClient;
    @MockitoBean MailNotificationClient mailNotificationClient;

    @Autowired UserRepository         userRepository;
    @Autowired PortfolioRepository    portfolioRepository;
    @Autowired DisclosureRepository   disclosureRepository;
    @Autowired NotificationRepository notificationRepository;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate    transactionTemplate;
    @Autowired AesGcmEncryptor        aesGcmEncryptor;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        portfolioRepository.deleteAll();
        disclosureRepository.deleteAll();
        userRepository.deleteAll();
        reset(mailNotificationClient, kakaoAlimtalkClient);
    }

    // ===== 1. 정상 발송 경로 =====

    @Test
    @DisplayName("EMAIL INSTANT 사용자 → POSITIVE 이벤트 → notifications SENT 기록 + mailClient.send 호출")
    void emailInstantUser_positiveEvent_recordsSentAndCallsMailClient() throws InterruptedException {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user = createUser("notif-1@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.85), false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> recs = notificationRepository.findByUserId(user.getId());
            assertThat(recs).hasSize(1);
            assertThat(recs.get(0).getStatus()).isEqualTo(Status.SENT);
            assertThat(recs.get(0).getChannel()).isEqualTo(Channel.EMAIL);
            assertThat(recs.get(0).getSentAt()).isNotNull();
        });

        verify(mailNotificationClient).send(eq(user.getEmail()), contains("호재"), any());
    }

    @Test
    @DisplayName("KAKAO INSTANT 사용자 + 전화번호 없음 → notifications FAILED 기록")
    void kakaoUser_noPhone_recordsFailed() {
        UserEntity user = createUser("notif-2@test.com", NotifyChannel.KAKAO, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("000660");
        createPortfolio(user.getId(), "000660");

        publishEventInTx(disclosure.getId(), Sentiment.NEGATIVE, BigDecimal.valueOf(0.70), false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> recs = notificationRepository.findByUserId(user.getId());
            assertThat(recs).hasSize(1);
            assertThat(recs.get(0).getStatus()).isEqualTo(Status.FAILED);
            assertThat(recs.get(0).getErrorMessage()).contains("Phone number");
        });

        verifyNoInteractions(kakaoAlimtalkClient);
    }

    @Test
    @DisplayName("KAKAO INSTANT 사용자 + 전화번호 있음 → kakaoClient.send 호출 + SENT 기록")
    void kakaoUser_withPhone_callsKakaoAndRecordsSent() {
        when(kakaoAlimtalkClient.send(any(), any())).thenReturn(true);

        byte[] encPhone = aesGcmEncryptor.encrypt("01012345678");
        UserEntity user = createUserWithPhone("notif-3@test.com", NotifyChannel.KAKAO, encPhone,
                true, NotifyFrequency.INSTANT, NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("035420");
        createPortfolio(user.getId(), "035420");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.90), false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> recs = notificationRepository.findByUserId(user.getId());
            assertThat(recs).hasSize(1);
            assertThat(recs.get(0).getStatus()).isEqualTo(Status.SENT);
        });

        verify(kakaoAlimtalkClient).send(eq("01012345678"), any());
    }

    // ===== 2. 필터 — withheld =====

    @Test
    @DisplayName("withheld=true → 발송 및 notifications 기록 없음")
    void withheld_noDispatch() {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user = createUser("notif-4@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.85), true); // withheld

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).isEmpty());
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 3. 필터 — notifyEnabled =====

    @Test
    @DisplayName("notifyEnabled=false → 발송 없음")
    void notifyDisabled_noDispatch() {
        UserEntity user = createUser("notif-5@test.com", NotifyChannel.EMAIL, false /* disabled */,
                NotifyFrequency.INSTANT, NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.80), false);

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).isEmpty());
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 4. 필터 — notify_type_filter =====

    @Test
    @DisplayName("POSITIVE_ONLY 사용자 + NEGATIVE 이벤트 → 발송 없음")
    void positiveOnlyFilter_negativeEvent_noDispatch() {
        UserEntity user = createUser("notif-6@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.POSITIVE_ONLY, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.NEGATIVE, BigDecimal.valueOf(0.80), false);

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).isEmpty());
        verifyNoInteractions(mailNotificationClient);
    }

    @Test
    @DisplayName("NEGATIVE_ONLY 사용자 + POSITIVE 이벤트 → 발송 없음")
    void negativeOnlyFilter_positiveEvent_noDispatch() {
        UserEntity user = createUser("notif-7@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.NEGATIVE_ONLY, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.80), false);

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).isEmpty());
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 5. 필터 — notify_frequency =====

    @Test
    @DisplayName("DAILY_1 빈도 사용자 → INSTANT 경로 skip (notifications 기록 없음)")
    void dailyFrequency_noInstantDispatch() {
        UserEntity user = createUser("notif-8@test.com", NotifyChannel.EMAIL, true,
                NotifyFrequency.DAILY_1, NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.80), false);

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).isEmpty());
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 6. 중복 발송 방지 (dedup) =====

    @Test
    @DisplayName("동일 (user, disclosure, channel)로 2회 이벤트 발행 → notifications 1건만 기록")
    void duplicateEvent_dedupToSingleNotification() {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user = createUser("notif-9@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.85), false);

        // 첫 번째 발송 완료 대기
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.findByUserId(user.getId())).hasSize(1)
        );

        // 동일 이벤트 재발행
        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.85), false);

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).size() == 1);

        assertThat(notificationRepository.findByUserId(user.getId())).hasSize(1);
        verify(mailNotificationClient, times(1)).send(any(), any(), any());
    }

    // ===== 7. 보유 종목 없는 공시 =====

    @Test
    @DisplayName("사용자가 해당 종목 미보유 → 포트폴리오 역조회 결과 없음 → notifications 기록 없음")
    void noPortfolioForStock_noDispatch() {
        UserEntity user = createUser("notif-10@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.ALL, true);
        // 포트폴리오를 등록하지 않음 — portfolioRepository.findByStockCode("005930") 결과 없음
        Disclosure disclosure = createDisclosure("005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.80), false);

        await().during(500, TimeUnit.MILLISECONDS).atMost(2, TimeUnit.SECONDS)
                .until(() -> notificationRepository.findByUserId(user.getId()).isEmpty());
        verifyNoInteractions(mailNotificationClient);
    }

    // ===== 8. 신뢰도 낮음 — 메시지 '판단 보류' 포함 =====

    @Test
    @DisplayName("confidence<0.5 → 메시지에 '판단 보류' 포함 + SENT 기록")
    void lowConfidence_messageContainsWithheldNote() {
        when(mailNotificationClient.send(any(), any(), any())).thenReturn(true);

        UserEntity user = createUser("notif-11@test.com", NotifyChannel.EMAIL, true, NotifyFrequency.INSTANT,
                NotifyTypeFilter.ALL, true);
        Disclosure disclosure = createDisclosure("005930");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.30) /* 낮은 신뢰도 */, false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationRepository.findByUserId(user.getId())).hasSize(1)
        );

        verify(mailNotificationClient).send(any(), any(), contains("판단 보류"));
    }

    // ===== fixture 헬퍼 =====

    private UserEntity createUser(String email, NotifyChannel channel, boolean notifyEnabled,
                                  NotifyFrequency frequency, NotifyTypeFilter filter, boolean offHoursAllowed) {
        return createUserWithPhone(email, channel, null, notifyEnabled, frequency, filter, offHoursAllowed);
    }

    private UserEntity createUserWithPhone(String email, NotifyChannel channel, byte[] phoneNumberEnc,
                                           boolean notifyEnabled, NotifyFrequency frequency,
                                           NotifyTypeFilter filter, boolean offHoursAllowed) {
        OffsetDateTime now = OffsetDateTime.now();
        return userRepository.save(UserEntity.builder()
                .email(email)
                .nickname("테스터-" + UUID.randomUUID().toString().substring(0, 4))
                .phoneNumberEnc(phoneNumberEnc)
                .notifyChannel(channel)
                .notifyEnabled(notifyEnabled)
                .notifyFrequency(frequency)
                .notifyTypeFilter(filter)
                .offHoursAllowed(offHoursAllowed)
                .termsAgreedAt(now)
                .privacyAgreedAt(now)
                .build());
    }

    private Disclosure createDisclosure(String stockCode) {
        return disclosureRepository.save(Disclosure.builder()
                .rceptNo(UUID.randomUUID().toString().replace("-", "").substring(0, 14))
                .corpCode("00000000")
                .stockCode(stockCode)
                .corpName("테스트회사")
                .reportNm("테스트 공시 보고서")
                .rceptDt(LocalDate.of(2026, 6, 8))
                .disclosureType("TREASURY_STOCK")
                .build());
    }

    private void createPortfolio(Long userId, String stockCode) {
        portfolioRepository.save(PortfolioEntity.builder()
                .userId(userId)
                .stockCode(stockCode)
                .build());
    }

    /**
     * @TransactionalEventListener(AFTER_COMMIT) 발화를 위해 TransactionTemplate 내에서 이벤트 발행.
     * 커밋 시점에 NotificationDispatcher.onAnalysisCompleted 비동기 실행.
     */
    private void publishEventInTx(Long disclosureId, Sentiment sentiment, BigDecimal confidence, boolean withheld) {
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new AnalysisCompletedEvent(
                    1L, disclosureId, sentiment, confidence, withheld));
            return null;
        });
    }
}
