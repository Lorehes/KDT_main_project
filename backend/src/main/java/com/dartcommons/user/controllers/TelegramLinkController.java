package com.dartcommons.user.controllers;

import com.dartcommons.user.dto.TelegramLinkResponse;
import com.dartcommons.user.services.TelegramLinkService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * [목적] 텔레그램 연동 REST 엔드포인트 — POST(딥링크 발급) / DELETE(연동 해제).
 * [이유] /notifications/settings(GET·PUT)와 리소스 성격이 달라(1회성 액션 vs 설정 CRUD) 별도 컨트롤러 분리.
 *       경로는 Spec R5: /api/v1/notifications/telegram/link.
 * [사이드 임팩트] 없음 — 발급은 인메모리 토큰만 생성, 해제는 telegram_chat_id null 처리.
 * [수정 시 고려사항] 연동 완료 자체는 이 API가 아니라 TelegramLinkPollingJob(/start 수거)이 수행 —
 *                  FE는 발급 후 settings 재조회(telegram_linked 폴링)로 완료를 감지한다.
 */
@RestController
@RequestMapping("/api/v1/notifications/telegram/link")
public class TelegramLinkController {

    private final TelegramLinkService telegramLinkService;

    public TelegramLinkController(TelegramLinkService telegramLinkService) {
        this.telegramLinkService = telegramLinkService;
    }

    /** 응답에 일회용 토큰 포함 — 브라우저/프록시 캐시 저장 방지(no-store). */
    @PostMapping
    public ResponseEntity<TelegramLinkResponse> issueLink(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new TelegramLinkResponse(telegramLinkService.issueLink(userId)));
    }

    @DeleteMapping
    public void unlink(@AuthenticationPrincipal Long userId) {
        telegramLinkService.unlink(userId);
    }
}
