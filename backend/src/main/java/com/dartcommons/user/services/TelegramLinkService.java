package com.dartcommons.user.services;

import com.dartcommons.infrastructure.telegram.TelegramProperties;
import com.dartcommons.shared.event.TelegramBotBlockedEvent;
import com.dartcommons.user.entities.UserEntity;
import com.dartcommons.user.repositories.UserRepository;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.RemovalCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/*
 * [목적] 텔레그램 계정 연동 — 일회용 딥링크 토큰 발급 + /start 토큰 소비로 chat_id 저장 + 봇 차단 이벤트 수신 해제.
 * [이유] 봇은 사용자에게 먼저 말을 걸 수 없어(텔레그램 정책) 사용자가 /start 해야 chat_id를 얻는다.
 *       딥링크(t.me/{bot}?start={token})의 토큰으로 "어느 회원의 /start인지"를 매칭 — chat_id 수동 입력
 *       경로를 원천 배제해 타인 chat 오연동을 차단한다(Spec 리스크 검토).
 *       토큰은 연동 1회성·10분 수명이라 DB 없이 인메모리로 충분(PhoneVerificationService OTP 패턴).
 *       [P0 리뷰 반영] 캐시 키를 userId(1인 1슬롯)로 잡아 단일 사용자의 발급 폭주가 타인의 대기 토큰을
 *       LRU 방출시키는 캐시 오염 DoS를 구조적으로 차단 — 재발급 시 자기 기존 토큰만 교체된다.
 *       토큰 소비는 tokenIndex.remove() 원자 연산(ConcurrentHashMap) — 동시 /start 이중 소비 차단.
 * [사이드 임팩트] 토큰 캐시는 인메모리 — 서버 재시작 시 대기 중 토큰 유실(사용자는 버튼 재클릭으로 복구).
 *               completeLink는 users.telegram_chat_id를 write — 재연동 시 덮어씀(멱등).
 *               notification 도메인의 봇 차단(403) 해제는 TelegramBotBlockedEvent 수신으로 처리(§3-2 write 경유 금지).
 * [수정 시 고려사항] 다중 인스턴스 전환 시 토큰 저장을 Redis로 이관(폴링 잡 분산 락과 함께 — Spec 후속 이슈).
 *                  딥링크 start payload는 텔레그램 규격상 최대 64자·[A-Za-z0-9_-] — 토큰 32자(TOKEN_LENGTH) 준수.
 *                  tokenIndex는 userTokens의 removalListener로 동기 정리 — TTL 만료 시에도 역인덱스 누수 없음.
 */
@Service
public class TelegramLinkService {

    private static final Logger log = LoggerFactory.getLogger(TelegramLinkService.class);

    private static final int TOKEN_BYTES = 24;
    /** base64url(24바이트) = 정확히 32자 — 폴링 잡의 사전 길이 검증에 사용. */
    public static final int TOKEN_LENGTH = 32;
    private static final int TOKEN_TTL_MINUTES = 10;

    private final UserRepository userRepository;
    private final TelegramProperties telegramProperties;
    private final SecureRandom secureRandom = new SecureRandom();

    /** token → userId 역인덱스. userTokens와 함께 유지 — 소비는 remove() 원자 연산. (userTokens 리스너가 참조 — 선언 순서 유지) */
    private final ConcurrentHashMap<String, Long> tokenIndex = new ConcurrentHashMap<>();

    /** userId → 대기 토큰. 1인 1슬롯 — 재발급 시 자기 토큰만 교체(캐시 오염 DoS 차단). TTL 10분. */
    private final Cache<Long, String> userTokens = Caffeine.newBuilder()
            .expireAfterWrite(TOKEN_TTL_MINUTES, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .removalListener((Long userId, String token, RemovalCause cause) -> {
                if (token != null) tokenIndex.remove(token);
            })
            .build();

    public TelegramLinkService(UserRepository userRepository, TelegramProperties telegramProperties) {
        this.userRepository = userRepository;
        this.telegramProperties = telegramProperties;
    }

    /** 일회용 연동 토큰 발급 + 딥링크 반환. 재클릭 시 기존 토큰은 즉시 무효화되고 새 토큰으로 교체(1인 1슬롯). */
    public String issueLink(Long userId) {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        userTokens.asMap().compute(userId, (id, oldToken) -> {
            if (oldToken != null) tokenIndex.remove(oldToken);
            tokenIndex.put(token, id);
            return token;
        });
        return "https://t.me/" + telegramProperties.botUsername() + "?start=" + token;
    }

    /**
     * /start 토큰 소비 — 매칭 성공 시 chat_id 저장 후 true, 만료/위조/이중 소비 토큰이면 false.
     * tokenIndex.remove()가 원자적 get-and-remove — 동시 /start 경쟁에서 한 쪽만 통과.
     */
    @Transactional
    public boolean completeLink(String token, String chatId) {
        Long userId = tokenIndex.remove(token);
        if (userId == null) {
            return false;
        }
        userTokens.invalidate(userId);
        return userRepository.findByIdAndDeletedAtIsNull(userId)
                .map(user -> {
                    user.linkTelegram(chatId);
                    userRepository.save(user);
                    log.info("Telegram linked for user {} (chatId=[REDACTED])", userId);
                    return true;
                })
                .orElse(false);
    }

    /** 폴링 잡의 호출 게이트 — 대기 토큰이 없으면 getUpdates 호출 자체를 생략(상시 폴링 낭비 방지). */
    public boolean hasPendingTokens() {
        return !tokenIndex.isEmpty();
    }

    /** 연동 해제 — 알림 설정 화면의 해제 버튼용. */
    @Transactional
    public void unlink(Long userId) {
        userRepository.findByIdAndDeletedAtIsNull(userId).ifPresent(user -> {
            user.unlinkTelegram();
            userRepository.save(user);
        });
    }

    /** 봇 차단(403) 감지 이벤트 수신 — notification 도메인의 user write 직접 침범 없이 chat_id 해제(§3-2). */
    @EventListener
    @Transactional
    public void onBotBlocked(TelegramBotBlockedEvent event) {
        userRepository.findByIdAndDeletedAtIsNull(event.userId()).ifPresent(user -> {
            user.unlinkTelegram();
            userRepository.save(user);
            log.warn("Telegram bot blocked by user {} — chat_id unlinked", event.userId());
        });
    }
}
