package com.dartcommons.notification.entities;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/*
 * [목적] notifications 테이블(V6) JPA 엔티티 — 알림 발송 이력 + PENDING→SENT/FAILED/RETRYING 상태머신.
 *       (user, disclosure, channel) 복합 UNIQUE(uq_notification_dedup)로 중복 발송 DB 2차 방어.
 * [이유] 발송 상태·재시도 횟수·오류 메시지를 영속화해 운영 모니터링·재시도 배치 기반 마련.
 *       Channel·Status enum은 V6 CHECK 제약과 @Enumerated(STRING)으로 동기화(CLAUDE.md §6-3).
 * [사이드 임팩트] NotificationDispatcher가 최초 PENDING save → 발송 결과에 따라 SENT/FAILED update.
 *               DataIntegrityViolationException(dedup 위반) → Dispatcher가 catch 후 skip.
 *               retry_count 증가·RETRYING 전환은 Wave 3+ NotificationRetryJob 담당.
 * [수정 시 고려사항] retry_count 증가는 별도 메서드(incrementRetry)로 캡슐화 권장.
 *                  error_message는 500자 truncate — 긴 스택트레이스는 로그에서 확인.
 */
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@Builder
public class NotificationEntity {

    public enum Channel { KAKAO, TELEGRAM, EMAIL }
    public enum Status  { PENDING, SENT, FAILED, RETRYING }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "disclosure_id", nullable = false)
    private Long disclosureId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 10)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 10)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(name = "retry_count", nullable = false)
    @Builder.Default
    private int retryCount = 0;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    private void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public void markSent() {
        this.status = Status.SENT;
        this.sentAt = OffsetDateTime.now();
    }

    public void markFailed(String message) {
        this.status = Status.FAILED;
        this.errorMessage = (message != null && message.length() > 500)
                ? message.substring(0, 500) : message;
    }
}
