package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.NotificationResponse;
import com.dartcommons.user.services.NotificationHistoryService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/*
 * [목적] GET /api/v1/notifications(알림 이력) + POST /api/v1/notifications/test(테스트 발송).
 * [이유] 알림 설정(NotificationSettingsController)과 이력/테스트를 분리해 단일 책임 유지.
 *       테스트 발송은 설정 저장 후 채널 동작 여부를 즉시 확인하는 사용자 셀프 검증 기능.
 * [사이드 임팩트] /notifications/settings (NotificationSettingsController)와 /notifications 이 경로가 충돌하지 않도록 prefix 구분됨.
 * [수정 시 고려사항] 알림 이력 페이지네이션 추가 시 @RequestParam page/size + PageResponse 반환으로 변경.
 *                  알림 읽음 처리(PATCH /notifications/{id}/read) 추가 시 이 컨트롤러에 메서드 추가.
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

    /** 설정 검증용 테스트 발송 — 채널 인프라 직접 호출, DB 이력 없음. */
    @PostMapping("/test")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void test(@AuthenticationPrincipal Long userId) {
        notificationHistoryService.sendTest(userId);
    }
}
