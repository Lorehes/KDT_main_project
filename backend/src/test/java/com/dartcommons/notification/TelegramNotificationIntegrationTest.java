package com.dartcommons.notification;

import com.dartcommons.TestcontainersConfiguration;
import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.analysis.repositories.AnalysisResultRepository;
import com.dartcommons.disclosure.DisclosurePollingJob;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.disclosure.repositories.DisclosureRepository;
import com.dartcommons.infrastructure.telegram.TelegramClient;
import com.dartcommons.notification.entities.NotificationEntity;
import com.dartcommons.notification.entities.NotificationEntity.Status;
import com.dartcommons.notification.repositories.NotificationRepository;
import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.shared.event.AnalysisCompletedEvent;
import com.dartcommons.user.TelegramLinkPollingJob;
import com.dartcommons.user.entities.PortfolioEntity;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.entities.UserEntity.NotifyChannel;
import com.dartcommons.user.entities.UserEntity.NotifyFrequency;
import com.dartcommons.user.entities.UserEntity.NotifyTypeFilter;
import com.dartcommons.user.repositories.PortfolioRepository;
import com.dartcommons.user.repositories.UserRepository;
import com.dartcommons.user.services.TelegramLinkService;
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
 * [목적] 텔레그램 채널 E2E 통합 테스트 — 발송(연동/미연동/봇차단) + 딥링크 연동 흐름(/start 토큰 소비).
 *       Testcontainers PostgreSQL 실 DB 검증(Mock DB 금지 — CLAUDE.md §6-6), V30 마이그레이션 validate 포함.
 * [이유] telegram-notification-channel Spec 카드 #14 — ChannelSender TELEGRAM 분기·chat_id 해제·
 *       TelegramLinkService 토큰 일회성이 회귀 없이 유지되는지 보장.
 * [사이드 임팩트] TelegramClient MockitoBean — 실 Bot API 호출 차단. 폴링 잡은 직접 인스턴스화해 검증
 *               (@Scheduled 대기 없이 결정적 실행).
 * [수정 시 고려사항] 다이제스트(DAILY_*) 경로 도입 시 TELEGRAM 다이제스트 케이스 추가.
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
class TelegramNotificationIntegrationTest {

    @MockitoBean DisclosurePollingJob pollingJob;
    @MockitoBean TelegramClient       telegramClient;

    @Autowired UserRepository            userRepository;
    @Autowired PortfolioRepository       portfolioRepository;
    @Autowired DisclosureRepository      disclosureRepository;
    @Autowired AnalysisResultRepository  analysisResultRepository;
    @Autowired NotificationRepository    notificationRepository;
    @Autowired TelegramLinkService       telegramLinkService;
    @Autowired ApplicationEventPublisher eventPublisher;
    @Autowired TransactionTemplate       transactionTemplate;

    @BeforeEach
    void setUp() {
        notificationRepository.deleteAll();
        analysisResultRepository.deleteAll();
        portfolioRepository.deleteAll();
        disclosureRepository.deleteAll();
        userRepository.deleteAll();
        reset(telegramClient);
    }

    // ===== 발송 경로 =====

    @Test
    @DisplayName("연동된 TELEGRAM 사용자 → SENT 기록 + chat_id로 send 호출 + HTML 본문(배지·3줄요약·면책)")
    void linkedUser_sent() {
        when(telegramClient.send(any(), any())).thenReturn(true);

        UserEntity user = createTelegramUser("tg-1@test.com", "111222333");
        Disclosure disclosure = createDisclosure("005930");
        AnalysisResult analysis = createAnalysis(disclosure.getId(), "전환사채 발행으로 주식 희석 가능성이 있습니다.");
        createPortfolio(user.getId(), "005930");

        publishEventInTx(analysis.getId(), disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.85), false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> recs = notificationRepository.findByUserId(user.getId());
            assertThat(recs).hasSize(1);
            assertThat(recs.get(0).getStatus()).isEqualTo(Status.SENT);
            assertThat(recs.get(0).getMessageBody()).contains("🔴 호재");
            assertThat(recs.get(0).getMessageBody()).contains("전환사채 발행으로 주식 희석 가능성이 있습니다.");
            assertThat(recs.get(0).getMessageBody()).contains("투자 자문·권유가 아닙니다");
        });

        verify(telegramClient).send(eq("111222333"), contains("호재"));
    }

    @Test
    @DisplayName("미연동 TELEGRAM 사용자 → FAILED(TELEGRAM_NOT_LINKED), send 미호출 — RetryJob 재시도 제외")
    void unlinkedUser_failedPermanently() {
        UserEntity user = createTelegramUser("tg-2@test.com", null);
        Disclosure disclosure = createDisclosure("000660");
        createPortfolio(user.getId(), "000660");

        publishEventInTx(disclosure.getId(), Sentiment.NEGATIVE, BigDecimal.valueOf(0.70), false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> recs = notificationRepository.findByUserId(user.getId());
            assertThat(recs).hasSize(1);
            assertThat(recs.get(0).getStatus()).isEqualTo(Status.FAILED);
            assertThat(recs.get(0).getErrorMessage()).isEqualTo("TELEGRAM_NOT_LINKED");
        });

        verify(telegramClient, never()).send(any(), any());
    }

    @Test
    @DisplayName("봇 차단(403) → FAILED(TELEGRAM_BLOCKED_BY_USER) + chat_id 자동 해제")
    void botBlocked_failedAndUnlinked() {
        when(telegramClient.send(any(), any()))
                .thenThrow(new TelegramClient.TelegramForbiddenException("blocked"));

        UserEntity user = createTelegramUser("tg-3@test.com", "999888777");
        Disclosure disclosure = createDisclosure("035420");
        createPortfolio(user.getId(), "035420");

        publishEventInTx(disclosure.getId(), Sentiment.POSITIVE, BigDecimal.valueOf(0.90), false);

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<NotificationEntity> recs = notificationRepository.findByUserId(user.getId());
            assertThat(recs).hasSize(1);
            assertThat(recs.get(0).getStatus()).isEqualTo(Status.FAILED);
            assertThat(recs.get(0).getErrorMessage()).isEqualTo("TELEGRAM_BLOCKED_BY_USER");
            assertThat(userRepository.findById(user.getId()).orElseThrow().getTelegramChatId()).isNull();
        });
    }

    // ===== 연동 흐름 =====

    @Test
    @DisplayName("딥링크 발급 → /start 토큰 수거(폴링 잡) → chat_id 저장 + 완료 메시지 발송")
    void linkFlow_startTokenConsumed() {
        UserEntity user = createTelegramUser("tg-4@test.com", null);

        String deepLink = telegramLinkService.issueLink(user.getId());
        assertThat(deepLink).startsWith("https://t.me/");
        String token = deepLink.substring(deepLink.indexOf("?start=") + "?start=".length());

        when(telegramClient.isDevMode()).thenReturn(false);
        when(telegramClient.getUpdates(anyLong())).thenReturn(List.of(
                new TelegramClient.Update(10L,
                        new TelegramClient.Message(new TelegramClient.Chat(555666777L), "/start " + token))));

        TelegramLinkPollingJob job = new TelegramLinkPollingJob(telegramClient, telegramLinkService);
        job.pollUpdates();

        assertThat(userRepository.findById(user.getId()).orElseThrow().getTelegramChatId())
                .isEqualTo("555666777");
        verify(telegramClient).send(eq("555666777"), contains("연동이 완료"));
    }

    @Test
    @DisplayName("토큰 일회성 — 소비된 토큰 재사용 시 연동 실패(만료 안내)")
    void linkToken_singleUse() {
        UserEntity user = createTelegramUser("tg-5@test.com", null);
        String deepLink = telegramLinkService.issueLink(user.getId());
        String token = deepLink.substring(deepLink.indexOf("?start=") + "?start=".length());

        assertThat(telegramLinkService.completeLink(token, "111")).isTrue();
        assertThat(telegramLinkService.completeLink(token, "222")).isFalse();
        assertThat(userRepository.findById(user.getId()).orElseThrow().getTelegramChatId()).isEqualTo("111");
    }

    @Test
    @DisplayName("위조/만료 토큰 /start → 연동 없음 + 만료 안내 메시지")
    void linkFlow_invalidToken_expiredReply() {
        UserEntity user = createTelegramUser("tg-6@test.com", null);
        telegramLinkService.issueLink(user.getId()); // 폴링 게이트 통과용 대기 토큰

        when(telegramClient.isDevMode()).thenReturn(false);
        when(telegramClient.getUpdates(anyLong())).thenReturn(List.of(
                new TelegramClient.Update(11L,
                        new TelegramClient.Message(new TelegramClient.Chat(123L), "/start bogus-token"))));

        TelegramLinkPollingJob job = new TelegramLinkPollingJob(telegramClient, telegramLinkService);
        job.pollUpdates();

        assertThat(userRepository.findById(user.getId()).orElseThrow().getTelegramChatId()).isNull();
        verify(telegramClient).send(eq("123"), contains("만료"));
    }

    // ===== fixture 헬퍼 =====

    private UserEntity createTelegramUser(String email, String chatId) {
        OffsetDateTime now = OffsetDateTime.now();
        return userRepository.save(UserEntity.builder()
                .email(email)
                .nickname("텔레그램러-" + UUID.randomUUID().toString().substring(0, 4))
                .notifyChannel(NotifyChannel.TELEGRAM)
                .notifyEnabled(true)
                .notifyFrequency(NotifyFrequency.INSTANT)
                .notifyTypeFilter(NotifyTypeFilter.ALL)
                .offHoursAllowed(true)
                .telegramChatId(chatId)
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
                .rceptDt(LocalDate.of(2026, 7, 6))
                .disclosureType("TREASURY_STOCK")
                .build());
    }

    private void createPortfolio(Long userId, String stockCode) {
        portfolioRepository.save(PortfolioEntity.builder()
                .userId(userId)
                .stockCode(stockCode)
                .build());
    }

    private AnalysisResult createAnalysis(Long disclosureId, String summary) {
        return analysisResultRepository.save(AnalysisResult.builder()
                .disclosureId(disclosureId)
                .sentiment(Sentiment.POSITIVE)
                .confidence(BigDecimal.valueOf(0.85))
                .summary(summary)
                .stageReached((short) 2)
                .build());
    }

    private void publishEventInTx(Long disclosureId, Sentiment sentiment, BigDecimal confidence, boolean withheld) {
        publishEventInTx(1L, disclosureId, sentiment, confidence, withheld);
    }

    private void publishEventInTx(Long analysisId, Long disclosureId, Sentiment sentiment,
                                  BigDecimal confidence, boolean withheld) {
        transactionTemplate.execute(status -> {
            eventPublisher.publishEvent(new AnalysisCompletedEvent(
                    analysisId, disclosureId, sentiment, confidence, withheld));
            return null;
        });
    }
}
