package com.dartcommons.infrastructure.telegram;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] application.yml dartcommons.telegram.* 를 타입 안전 record로 바인딩하는 설정 객체.
 * [이유] CLAUDE.md §7: 봇 토큰 하드코딩 금지 — 환경변수(TELEGRAM_BOT_TOKEN) 주입.
 *       KakaoAlimtalkProperties와 동일 패턴(@ConfigurationProperties + @Validated).
 *       frontBaseUrl은 알림 메시지의 "상세 분석 보기" 링크 조립에 사용(NotificationMessageBuilder).
 * [사이드 임팩트] TelegramClient·NotificationMessageBuilder·TelegramLinkService가 이 properties에 의존.
 *               botToken 미설정 시 기본값 placeholder → dev 모드(실 API 미호출, KakaoAlimtalkClient와 동일 규약).
 * [수정 시 고려사항] 토큰 유출 시 BotFather /revoke 재발급 후 환경변수만 교체 — 코드 변경 불필요.
 *                  botUsername은 딥링크(t.me/{username}?start=...) 조립용 — 봇 교체 시 함께 갱신.
 */
@ConfigurationProperties("dartcommons.telegram")
@Validated
public record TelegramProperties(
        @DefaultValue("https://api.telegram.org") String baseUrl,
        // @DefaultValue: yml 키 자체가 없는 환경(CI 등)에서도 dev 모드로 안전 부팅 — @NotBlank는 빈 문자열 주입 방어
        @NotBlank @DefaultValue("placeholder") String botToken,
        @NotBlank @DefaultValue("placeholder_bot") String botUsername,
        @DefaultValue("http://localhost:3000") String frontBaseUrl,
        @DefaultValue("10000") int timeoutMs,
        @DefaultValue("3") int maxRetries
) {
    /** placeholder 토큰이면 개발 모드 — 실제 Telegram API 호출 없이 로그 출력으로 대체. */
    public boolean isDevMode() {
        return "placeholder".equals(botToken);
    }
}
