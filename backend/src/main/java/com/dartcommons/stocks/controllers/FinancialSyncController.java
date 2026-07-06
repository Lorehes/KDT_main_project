package com.dartcommons.stocks.controllers;

import com.dartcommons.stocks.services.FinancialSyncService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/*
 * [목적] 재무 스냅샷 시드 백필·수동 동기화 관리자 REST 엔드포인트.
 *       POST /admin/financial/sync-quarter: 특정 연도·분기 수집 (단기 — 341콜, ~3분).
 *       POST /admin/financial/seed-backfill: 8분기 일괄 수집 → **202 즉시 반환** (장기 — ~27분).
 * [이유] nginx proxy_read_timeout=60s 제약 — 341종목 × 500ms throttle × 1분기 ≈ 3분.
 *       sync-quarter(단일 분기)는 nginx 60s를 초과할 수 있으므로 비동기 처리.
 *       seed-backfill(8분기 ≈ 27분)은 무조건 비동기(202 Accepted).
 * [사이드 임팩트] /admin/** SecurityConfig HTTP Basic 자동 보호.
 *               비동기 실행 결과 확인은 서버 로그(FinancialSyncService 완료 로그) 참조.
 *               DART 일 쿼터 소비: 단일 분기 341콜, 8분기 시드 ~2,728콜(일 20k의 14%).
 * [수정 시 고려사항] 진행률 조회 API가 필요하면 Stage4BackfillController의 jobId 패턴으로 확장.
 *                  반기(11012)·1Q/3Q 수집은 sync-quarter의 reprt_code 파라미터로 가능.
 */
@RestController
@RequestMapping("/admin/financial")
public class FinancialSyncController {

    private static final Set<String> ALLOWED_REPRT_CODES = Set.of("11011", "11012", "11013", "11014");

    private final FinancialSyncService syncService;

    public FinancialSyncController(FinancialSyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * 특정 연도·분기 수집 — 비동기 202 반환(nginx 60s 타임아웃 회피).
     * 341콜 × 500ms ≈ 3분. 완료는 서버 로그 확인.
     */
    @PostMapping("/sync-quarter")
    public ResponseEntity<Map<String, Object>> syncQuarter(
            @RequestParam String bsnsYear,
            @RequestParam(defaultValue = "11011") String reprtCode) {
        if (!ALLOWED_REPRT_CODES.contains(reprtCode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "reprtCode 허용 목록: " + ALLOWED_REPRT_CODES);
        }
        // ponytail: 완전한 jobId 패턴 대신 단순 비동기 fire-and-forget — 진행률 조회 불필요한 1회성 운영 작업
        CompletableFuture.runAsync(() -> syncService.syncQuarter(bsnsYear, reprtCode));
        return ResponseEntity.accepted()
                .body(Map.of("bsnsYear", bsnsYear, "reprtCode", reprtCode,
                        "message", "비동기 수집 시작. 서버 로그에서 완료 확인."));
    }

    /**
     * 초기 시드 백필 — 최근 N개 사업연도 사업보고서(11011) 일괄 수집.
     * 기본: 현재 연도 기준 최근 8개 사업연도(2개 사업연도 = 1~4Q 포함).
     * ※ "8분기" 표현은 실제로 8개 연간 사업보고서(11011) — 분기 코드가 아닌 연도 단위.
     *    반기·분기 보고서까지 필요하면 reprtCode 파라미터로 sync-quarter를 별도 호출.
     */
    @PostMapping("/seed-backfill")
    public ResponseEntity<Map<String, Object>> seedBackfill(
            @RequestParam(required = false) List<String> years) {
        List<String> targets = buildYears(years);
        CompletableFuture.runAsync(() -> syncService.seedBackfill(targets));
        return ResponseEntity.accepted()
                .body(Map.of("years", targets, "estimatedCalls", targets.size() * 341,
                        "message", "비동기 시드 백필 시작(~" + targets.size() + "분기). 서버 로그에서 완료 확인."));
    }

    private static List<String> buildYears(List<String> input) {
        if (input != null && !input.isEmpty()) return input;
        int current = LocalDate.now().getYear();
        List<String> result = new ArrayList<>(8);
        for (int i = 1; i <= 8; i++) result.add(String.valueOf(current - i));
        return result;
    }
}
