package com.dartcommons.infrastructure.dart;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/*
 * [목적] application.yml dartcommons.dart.* 를 타입 안전 record로 바인딩하는 설정 객체.
 * [이유] DART API 키를 환경변수로만 주입하는 CLAUDE.md §7 규칙 준수.
 *       하드코딩 방지와 테스트 환경별 오버라이드 지원.
 * [사이드 임팩트] DartClient/DartCorpCodeClient/DartDocumentClient 빈이 이 properties에 직접 의존.
 *               apiKey가 공백이면 DART status=020 반환. @Validated + @NotBlank로 빠른 실패.
 *               documentTimeoutMs 는 document.xml zip(대용량) 전용 — list.json timeoutMs보다 크게 설정.
 *               contentMaxChars 초과 시 DisclosureContentService 계층에서 truncate(원문은 attachment_url 참조).
 *               contentBackfillChunkSize: DisclosureContentBackfillService 커서 루프 청크 크기.
 * [수정 시 고려사항] base-url 변경 없이 api-key만 교체하면 운영 키 롤링 가능.
 *                  contentBackfillThrottleMs — 93k 백필 시 DART 일일 한도 내 속도 조절.
 *                  DART 일일 호출 한도 실측 전 contentBackfillThrottleMs=1000 이상 권장.
 *                  contentBackfillChunkSize 조정 시 safety cap(estimated/chunkSize × 2)에 영향 — 너무 크면 단일 청크 DART 부하 증가.
 */
@ConfigurationProperties("dartcommons.dart")
@Validated
public record DartApiProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @DefaultValue("10000") int timeoutMs,
        @DefaultValue("3") int maxRetries,
        /** document.xml zip 응답 전용 read timeout. list.json보다 대용량이므로 별도 설정. */
        @DefaultValue("30000") int documentTimeoutMs,
        /** content_text 최대 글자 수. 초과 시 truncate (임베딩 비용 상한). */
        @DefaultValue("50000") int contentMaxChars,
        /** 백필 요청 간 스로틀 대기(ms). DART 일일 한도 보호. */
        @DefaultValue("500") long contentBackfillThrottleMs,
        /** 백필 커서 청크 크기. 청크당 DART 호출 건수 상한 — DART 일일 한도·Ollama RPS 맞춤. */
        @DefaultValue("100") int contentBackfillChunkSize
) {
}
