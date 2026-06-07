package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.NotificationSettingsRequest;
import com.dartcommons.user.dto.NotificationSettingsResponse;
import com.dartcommons.user.services.NotificationSettingsService;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 알림 설정 조회·변경 REST 엔드포인트 — GET/PUT /api/v1/users/me/notifications.
 * [이유] 알림 설정은 빈번히 독립 변경 — 전체 프로필 PATCH 대신 전용 엔드포인트로 분리.
 *       PUT(전체 교체): 부분 업데이트보다 클라이언트 구현이 단순(모든 설정 항목 명시 필수).
 * [사이드 임팩트] 없음.
 * [수정 시 고려사항] 알림 채널 변경(KAKAO→EMAIL) 시 phone_number 검증 로직 추가 가능.
 */
@RestController
@RequestMapping("/api/v1/users/me/notifications")
public class NotificationSettingsController {

    private final NotificationSettingsService notificationSettingsService;

    public NotificationSettingsController(NotificationSettingsService notificationSettingsService) {
        this.notificationSettingsService = notificationSettingsService;
    }

    @GetMapping
    public NotificationSettingsResponse getSettings(@AuthenticationPrincipal Long userId) {
        return notificationSettingsService.getSettings(userId);
    }

    @PutMapping
    public NotificationSettingsResponse updateSettings(@AuthenticationPrincipal Long userId,
                                                       @Valid @RequestBody NotificationSettingsRequest request) {
        return notificationSettingsService.updateSettings(userId, request);
    }
}
