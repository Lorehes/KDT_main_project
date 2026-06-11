package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.NotificationResponse;
import com.dartcommons.user.services.NotificationHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/*
 * [목적] GET /api/v1/notifications(알림 이력) + PATCH 읽음 처리 + GET unread-count + POST test(테스트 발송).
 * [이유] 알림 설정(NotificationSettingsController)과 이력/테스트를 분리해 단일 책임 유지.
 *       테스트 발송은 설정 저장 후 채널 동작 여부를 즉시 확인하는 사용자 셀프 검증 기능.
 *       읽음 처리(PATCH)는 FE 로컬 Set 임시 처리를 대체 — 서버 영속화로 세션 간 상태 유지.
 * [사이드 임팩트] /notifications/settings (NotificationSettingsController)와 /notifications 이 경로가 충돌하지 않도록 prefix 구분됨.
 *               PATCH /{id}/read: NotificationHistoryService에서 userId 소유권 검증 — IDOR 방어.
 *               PATCH /read-all: bulk UPDATE, 204 반환.
 *               GET /unread-count: TopBar 벨 뱃지 폴링용. FE 30초 staleTime 설정.
 * [수정 시 고려사항] 알림 이력 페이지네이션 추가 시 @RequestParam page/size + PageResponse 반환으로 변경.
 *                  WebSocket 도입 시 unread-count 폴링 → 서버 푸시로 전환 가능.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationHistoryService notificationHistoryService;

    public NotificationController(NotificationHistoryService notificationHistoryService) {
        this.notificationHistoryService = notificationHistoryService;
    }

    /** 알림 발송 이력 조회 (createdAt DESC). */
    @GetMapping
    public List<NotificationResponse> list(@AuthenticationPrincipal Long userId) {
        return notificationHistoryService.list(userId);
    }

    /** 단건 읽음 처리 — IDOR 방어: userId 소유권 검증 후 처리. 204 No Content. */
    @PatchMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@AuthenticationPrincipal Long userId, @PathVariable Long id) {
        notificationHistoryService.markRead(userId, id);
    }

    /** 전체 읽음 처리 — bulk UPDATE. 204 No Content. */
    @PatchMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@AuthenticationPrincipal Long userId) {
        notificationHistoryService.markAllRead(userId);
    }

    /** 미읽음 알림 수 조회 — TopBar 벨 뱃지용. */
    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount(@AuthenticationPrincipal Long userId) {
        return Map.of("count", notificationHistoryService.getUnreadCount(userId));
    }

    /** 설정 검증용 테스트 발송 — 채널 인프라 직접 호출, DB 이력 없음. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@AuthenticationPrincipal Long userId) {
        notificationHistoryService.sendTest(userId);
    }
}
