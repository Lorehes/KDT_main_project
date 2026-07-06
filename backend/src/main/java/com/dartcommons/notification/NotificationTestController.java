package com.dartcommons.notification;

import com.dartcommons.notification.services.NotificationDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/*
 * [목적] 관리자 수동 알림 발송 트리거 — 특정 공시에 대해 즉시 발송 테스트.
 * [이유] 백필 잡은 AnalysisCompletedEvent를 발화하지 않아 알림이 안 감.
 *       운영자가 특정 공시의 알림 발송을 수동으로 확인할 수 있도록 한시 제공.
 * [수정 시 고려사항] /admin/** → HTTP Basic 인증(SecurityConfig). 프로덕션 노출 최소화.
 */
@RestController
@RequestMapping("/admin/notifications")
@RequiredArgsConstructor
public class NotificationTestController {

    private final NotificationDispatcher dispatcher;

    @PostMapping("/test")
    public ResponseEntity<String> test(@RequestParam Long disclosureId) {
        dispatcher.dispatchForDisclosure(disclosureId);
        return ResponseEntity.ok("dispatched for disclosureId=" + disclosureId);
    }
}
