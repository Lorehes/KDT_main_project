package com.dartcommons.user;

import com.dartcommons.infrastructure.telegram.TelegramClient;
import com.dartcommons.user.services.TelegramLinkService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/*
 * [목적] 텔레그램 getUpdates 폴링 잡 — 봇이 수신한 "/start {token}" 메시지를 수거해 계정 연동을 완성.
 * [이유] Tech Review A안: webhook(인바운드 HTTPS 엔드포인트)은 보안 면적이 늘고, 연동은 가입 후 1회성
 *       이벤트라 5초 폴링 지연이 UX에 무해. 대기 토큰이 없으면 API 호출 자체를 생략(상시 폴링 낭비 없음).
 * [사이드 임팩트] getUpdates는 webhook과 상호 배타 — 봇에 webhook 설정 시 이 잡이 409로 실패한다(운영가이드 명시).
 *               lastUpdateId는 인메모리(AtomicLong) — 재시작 시 0부터 재조회하나, 소비된 토큰은 재매칭
 *               불가(일회용)라 중복 연동 부작용 없음. 텔레그램 서버는 offset 확인분을 24h 후 폐기.
 *               dev 모드(placeholder 토큰)면 TelegramClient.getUpdates가 빈 리스트 반환 → 사실상 no-op.
 * [수정 시 고려사항] 다중 인스턴스 배포 시 두 인스턴스가 getUpdates를 경쟁 호출하면 409 충돌 —
 *                  ShedLock + offset 외부화(Redis/DB)로 이관(Spec 후속 이슈, MVP 단일 인스턴스 전제).
 *                  @ConditionalOnProperty 제거 금지 — 테스트 전역(scheduling.enabled=false)에서 실 API 호출 차단.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "dartcommons.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class TelegramLinkPollingJob {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkPollingJob.class);

    private static final String START_COMMAND = "/start";

    private static final String LINK_SUCCESS_MESSAGE =
            "✅ DartCommons 연동이 완료되었습니다.\n"
            + "이제 보유 종목의 공시 분석 알림을 이 대화방으로 받아보실 수 있어요.";

    private static final String LINK_EXPIRED_MESSAGE =
            "⚠️ 연동 링크가 만료되었거나 올바르지 않습니다.\n"
            + "웹 알림 설정 화면에서 \"텔레그램 연동\"을 다시 눌러주세요.";

    private final TelegramClient telegramClient;
    private final TelegramLinkService telegramLinkService;

    /** 마지막으로 확인한 update_id — getUpdates(offset=last+1) 호출로 재수신 방지. */
    private final AtomicLong lastUpdateId = new AtomicLong(0);

    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void pollUpdates() {
        // 게이트: dev 모드 또는 연동 대기 토큰 없음 → API 호출 생략
        if (telegramClient.isDevMode() || !telegramLinkService.hasPendingTokens()) {
            return;
        }

        List<TelegramClient.Update> updates;
        try {
            updates = telegramClient.getUpdates(lastUpdateId.get() + 1);
        } catch (Exception e) {
            log.warn("Telegram getUpdates failed: {}", e.getClass().getSimpleName());
            return;
        }

        for (TelegramClient.Update update : updates) {
            lastUpdateId.updateAndGet(prev -> Math.max(prev, update.updateId()));
            try {
                handleUpdate(update);
            } catch (Exception e) {
                // 건별 격리 — 1건 처리 실패가 나머지 업데이트 소비를 막지 않음
                log.warn("Telegram update {} handling failed: {}", update.updateId(), e.getClass().getSimpleName());
            }
        }
    }

    /** "/start {token}" 만 처리 — 그 외 메시지는 무시(offset만 전진). */
    private void handleUpdate(TelegramClient.Update update) {
        TelegramClient.Message message = update.message();
        if (message == null || message.chat() == null || message.text() == null) {
            return;
        }
        String text = message.text().strip();
        if (!text.startsWith(START_COMMAND)) {
            return;
        }
        String chatId = String.valueOf(message.chat().id());
        String token = text.length() > START_COMMAND.length()
                ? text.substring(START_COMMAND.length()).strip()
                : "";

        // 유효 토큰은 정확히 32자(base64url 24바이트) — 공격성 대형 payload를 캐시 조회 전에 차단
        boolean linked = token.length() == TelegramLinkService.TOKEN_LENGTH
                && telegramLinkService.completeLink(token, chatId);
        sendReplyQuietly(chatId, linked ? LINK_SUCCESS_MESSAGE : LINK_EXPIRED_MESSAGE);
    }

    /** 연동 확인 메시지는 부가 UX — 발송 실패해도 연동 결과에 영향 없음(로그만). */
    private void sendReplyQuietly(String chatId, String message) {
        try {
            telegramClient.send(chatId, message);
        } catch (Exception e) {
            log.debug("Telegram link reply send failed: {}", e.getClass().getSimpleName());
        }
    }
}
