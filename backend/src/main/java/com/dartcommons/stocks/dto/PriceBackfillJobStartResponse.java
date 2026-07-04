package com.dartcommons.stocks.dto;

import java.util.UUID;

/*
 * [목적] 주가 백필 잡 시작(202 Accepted) 응답 DTO — jobId와 초기 상태 반환.
 * [이유] 타입 안전 record. 클라이언트는 jobId로 GET /jobs/{jobId} 폴링(krx-price-timeseries Wave B).
 * [사이드 임팩트] 관리자 전용 POST /admin/stocks/price-backfill 응답에만 사용.
 * [수정 시 고려사항] 409 응답은 공통 ProblemDetail 사용 권장.
 */
public record PriceBackfillJobStartResponse(
        UUID jobId,
        String status,
        String message
) {}
