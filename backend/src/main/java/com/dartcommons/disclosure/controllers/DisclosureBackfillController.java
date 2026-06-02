package com.dartcommons.disclosure.controllers;

import com.dartcommons.disclosure.services.DisclosureBackfillService;
import com.dartcommons.disclosure.services.DisclosureBackfillService.BackfillResult;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/*
 * [목적] 임의 날짜 범위 공시 백필을 트리거하는 관리자용 REST 엔드포인트.
 * [이유] 운영 시점에 3년치 등 과거 공시를 일괄 적재하려면 폴링 잡 외 별도 진입점 필요.
 *       CLI 스크립트보다 REST가 운영 자동화/모니터링과 잘 결합(curl 호출, 진행 로그 tail).
 * [사이드 임팩트] **인증/권한 가드 미적용** — 현재 Spring Security 미도입 상태(통합기획서 §11).
 *               운영 배포 전 반드시 관리자 권한 가드 추가(IDOR/권한 우회 방지).
 *               긴 호출(시간 단위) 동안 HTTP 커넥션 유지 — 비동기 응답(202 Accepted + jobId) 고려.
 *               백필 동안 정상 폴링이 동시 동작 — rcept_no 멱등으로 충돌 없음.
 * [수정 시 고려사항] Spring Security 도입 후 @PreAuthorize("hasRole('ADMIN')") 필수.
 *                  대형 작업은 비동기(@Async) + 진행률 조회 별도 엔드포인트로 분리 권장.
 *                  요청자 IP/사용자 감사 로그 추가.
 *                  운영 가이드: 분기당 1회 수동 호출, off-peak 시간대 권장.
 */
@RestController
@RequestMapping("/admin/disclosures")
@RequiredArgsConstructor
public class DisclosureBackfillController {

    private final DisclosureBackfillService backfillService;

    /**
     * 백필 트리거.
     * <p>
     * 예: {@code POST /admin/disclosures/backfill?from=2023-06-01&to=2026-06-01&emitEvents=false}
     *
     * @param from 시작일 (yyyy-MM-dd)
     * @param to 종료일 (yyyy-MM-dd)
     * @param emitEvents 분석 도메인 이벤트 발행 여부 — 기본 false (분석은 별도 배치로 트리거)
     */
    @PostMapping("/backfill")
    public ResponseEntity<BackfillResult> backfill(
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean emitEvents) {
        BackfillResult result = backfillService.backfill(from, to, emitEvents);
        return ResponseEntity.ok(result);
    }
}
