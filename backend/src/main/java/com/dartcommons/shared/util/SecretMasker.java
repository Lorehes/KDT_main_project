package com.dartcommons.shared.util;

import java.util.regex.Pattern;

/*
 * [목적] 외부 API 호출 에러 메시지에서 시크릿(API 키 등)을 마스킹.
 * [이유] RestClientException 등 예외 메시지에 URL 쿼리스트링(crtfc_key=실제키...)이 포함될 수 있음 —
 *       로그·알림·모니터링에 평문 키 노출 차단(CLAUDE.md §7).
 * [사이드 임팩트] 로그·예외 메시지에만 적용. 정상 응답 본문 마스킹은 아님(별도 필요 시 추가).
 *               정규식 매칭 — 매번 호출 시 매처 생성 비용 미미하지만 컴파일된 Pattern 상수 재사용.
 * [수정 시 고려사항] 추가 키 파라미터(`appkey`, `token` 등)는 KEY_PARAM_PATTERN에 추가.
 *                  HTTP 헤더 마스킹은 본 유틸 범위 외 — RestClient interceptor에서 처리.
 */
public final class SecretMasker {

    private static final Pattern KEY_PARAM_PATTERN = Pattern.compile(
            "(crtfc_key|api_key|apikey|appkey|token|secret)=[^&\\s\"']+",
            Pattern.CASE_INSENSITIVE);

    private SecretMasker() {
    }

    /**
     * 문자열 내 쿼리 파라미터 형태의 시크릿 값을 `***`로 마스킹.
     * null 입력은 null 반환(예외 메시지 null 가능성 방어).
     */
    public static String mask(String input) {
        if (input == null) return null;
        return KEY_PARAM_PATTERN.matcher(input).replaceAll("$1=***");
    }
}
