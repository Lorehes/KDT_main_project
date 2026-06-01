package com.dartcommons.infrastructure.dart;

import com.dartcommons.infrastructure.dart.dto.DartListResponse;

/*
 * [목적] DART OpenAPI가 status != "000"을 반환할 때 발생하는 도메인 예외.
 * [이유] HTTP 200이어도 DART status로 오류를 알리므로, 일반 RestClient 예외와 분리해 처리.
 * [사이드 임팩트] DartClient에서 throw → DisclosurePollingJob이 catch 후 로깅.
 * [수정 시 고려사항] status=020(키 오류)/800/900은 운영 알림 연계가 필요한 심각 오류.
 */
public class DartApiException extends RuntimeException {

    private final String dartStatus;

    public DartApiException(String dartStatus, String message) {
        super("[DART status=" + dartStatus + "] " + message);
        this.dartStatus = dartStatus;
    }

    public String getDartStatus() {
        return dartStatus;
    }

    /** 즉시 운영 알림이 필요한 심각 상태(키 오류·시스템 장애) */
    public boolean isCritical() {
        return DartListResponse.STATUS_KEY_ERROR.equals(dartStatus)
                || DartListResponse.STATUS_SYSTEM_ERROR_800.equals(dartStatus)
                || DartListResponse.STATUS_SYSTEM_ERROR_900.equals(dartStatus);
    }
}
