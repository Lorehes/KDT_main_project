package com.dartcommons.shared.event;

/*
 * [목적] 텔레그램 봇 차단(403) 감지 도메인 이벤트 — notification → user 방향의 chat_id 해제 요청.
 * [이유] ChannelSender(notification)가 UserRepository를 직접 write하면 도메인 간 직접 의존 금지(§3-2) 위반
 *       — shared 이벤트 경유로 결합도 제거(dc-review-code P1 반영).
 * [사이드 임팩트] 수신자는 TelegramLinkService.onBotBlocked(@EventListener, 동기) — 발행 스레드에서 즉시 처리.
 * [수정 시 고려사항] 발송 경로가 @Async 스레드라 발행-수신 모두 notificationExecutor 컨텍스트에서 실행됨.
 */
public record TelegramBotBlockedEvent(Long userId) {
}
