package com.dartcommons.disclosure.controllers;

import com.dartcommons.disclosure.services.DisclosureContentBackfillService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/*
 * [목적] 공시 본문 fetch 백필을 수동으로 트리거하는 관리자용 REST 엔드포인트.
 *       POST /admin/disclosures/content-backfill: 백필 시작(비동기, 즉시 202 반환).
 *       GET  /admin/disclosures/content-backfill/status: 현재 실행 여부 조회.
 * [이유] disclosure-content-text-fetch Spec MEDIUM-5: 운영 중 재처리 시 배포 없이 대응 필요.
 *       DisclosureContentBackfillService.runBackfill()은 @Async이므로 HTTP 타임아웃 무관.
 * [사이드 임팩트] /admin/** 경로는 SecurityConfig가 HTTP Basic(ROLE_ADMIN) 인증 강제(DisclosureBackfillController 패턴).
 *               중복 호출은 AtomicBoolean CAS로 서비스 계층에서 차단 — 응답 200 + running=true로 안내.
 * [수정 시 고려사항] 요청자 감사 로그(IP/사용자명)는 운영 배포 시 SecurityConfig AuditListener로 추가 권장.
 *                  진행률 지속적 조회가 필요하면 SSE 또는 GET /status 주기 폴링으로 구현.
 *                  다중 인스턴스 환경(Kubernetes)에서는 분산 락이 필요 — content-fetch-backfill-resilience Spec 후속.
 */
@RestController
@RequestMapping("/admin/disclosures/content-backfill")
@RequiredArgsConstructor
public class DisclosureContentBackfillController {

    private final DisclosureContentBackfillService backfillService;

    /**
     * 본문 백필 시작 — 이미 실행 중이면 200 + running=true 반환(중복 시작 없음).
     * 비동기 실행이므로 HTTP 연결 즉시 해제.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> startBackfill() {
        boolean wasRunning = backfillService.isRunning();
        if (!wasRunning) {
            backfillService.runBackfill();
        }
        return ResponseEntity.ok(Map.of(
                "started", !wasRunning,
                "running", true,
                "message", wasRunning
                        ? "Backfill already in progress. No duplicate started."
                        : "Backfill started asynchronously. Monitor server logs for progress."
        ));
    }

    /** 백필 실행 상태 조회 — running=true/false. */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("running", backfillService.isRunning()));
    }
}
