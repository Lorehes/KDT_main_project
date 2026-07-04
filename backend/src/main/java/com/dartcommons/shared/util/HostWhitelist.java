package com.dartcommons.shared.util;

import java.net.URI;
import java.util.Set;

/*
 * [목적] 외부 API 클라이언트의 baseUrl 호스트를 화이트리스트로 검증해 SSRF 차단.
 * [이유] DartApiProperties/KrxApiProperties/LlmProperties.baseUrl이 환경변수 주입이라 외부 공격자가 직접 변경할 수는 없으나,
 *       설정 오류·공급망 공격으로 내부 서비스 URL이 주입되면 SSRF 위험 — 빠른 실패로 방어.
 * [사이드 임팩트] 허용 외 호스트면 부팅 시 IllegalStateException → 애플리케이션 구동 실패(의도된 빠른 실패).
 *               openrouter.ai 추가(llm-production-switch Spec) — OpenRouterLlmClient 생성자에서 verify() 호출.
 *               테스트는 localhost(Testcontainers/MockServer 등) 허용 — TEST_ALLOWED에 명시.
 * [수정 시 고려사항] 새 외부 API 도입 시 PROD_ALLOWED에 호스트 추가. 추가 누락 시 부팅 실패로 즉시 발견.
 *                  사설망 IP(192.168.x.x, 10.x.x.x) 별도 차단 정책은 NetworkPolicy(인프라 계층)에 위임.
 *                  KrxClient.externalRestClient(B128·GitHub cache)는 화이트리스트 검증 밖에 있음.
 *                  단, 호출 URL은 컴파일 상수(B128_URL·GITHUB_CACHE_URL)로 고정 — 동적 입력 절대 금지.
 *                  향후 externalRestClient 호출 URL을 환경변수로 외부화하면 verify() 등록 필요.
 */
public final class HostWhitelist {

    private static final Set<String> PROD_ALLOWED = Set.of(
            "opendart.fss.or.kr",           // DART OpenAPI
            "data.krx.co.kr",              // KRX 정보데이터시스템
            "alimtalk-api.kakao.com",       // 카카오 비즈메시지 알림톡
            "openrouter.ai"                 // OpenRouter Cloud LLM API (llm-production-switch Spec)
    );

    private static final Set<String> TEST_ALLOWED = Set.of(
            "localhost",
            "127.0.0.1",
            "host.docker.internal"      // Testcontainers
    );

    private HostWhitelist() {
    }

    /**
     * baseUrl의 호스트가 허용 목록에 있는지 검증. 위반 시 IllegalStateException throw.
     *
     * @param baseUrl 검증 대상 URL (예: "https://opendart.fss.or.kr/api")
     * @param clientName 로그 식별용 이름 (예: "DartClient")
     */
    public static void verify(String baseUrl, String clientName) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(clientName + ": baseUrl is blank");
        }
        URI uri;
        try {
            uri = URI.create(baseUrl);
        } catch (Exception e) {
            throw new IllegalStateException(clientName + ": invalid baseUrl: " + baseUrl, e);
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalStateException(clientName + ": baseUrl has no host: " + baseUrl);
        }
        if (PROD_ALLOWED.contains(host) || TEST_ALLOWED.contains(host)) {
            return;
        }
        throw new IllegalStateException(
                clientName + ": baseUrl host '" + host + "' not in whitelist (prod=" + PROD_ALLOWED + ", test=" + TEST_ALLOWED + ")");
    }
}
