package com.dartcommons.infrastructure.krx;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] application.yml dartcommons.krx.* 를 타입 안전 record로 바인딩하는 설정 객체.
 *       KRX OpenAPI 호출용 — 종목 마스터 갱신(StockMasterSyncJob)이 소비.
 * [이유] KRX API 키 환경변수 주입 규칙(CLAUDE.md §7) + 테스트 환경별 오버라이드.
 *       baseUrl/endpoint는 KRX OpenAPI 가이드 확정 후 application.yml에서 갱신(현재 스켈레톤).
 * [사이드 임팩트] KrxClient 빈이 이 properties에 의존. apiKey 미주입 시 부팅 실패(빠른 실패).
 *               DartApiProperties와 동일 패턴 — 키 롤링·timeout 조정은 application.yml만 수정.
 * [수정 시 고려사항] KRX OpenAPI 실측(stocks-master-seed Spec 카드 #1) 완료 후
 *                  엔드포인트별 경로(stock-info 등)는 KrxClient 내부 상수 또는 추가 필드로 분리.
 *                  rate limit 정책은 KRX 측 확인 후 max-retries/timeout-ms 조정.
 */
@ConfigurationProperties("dartcommons.krx")
@Validated
public record KrxApiProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @DefaultValue("10000") int timeoutMs,
        @DefaultValue("3") int maxRetries
) {
}
