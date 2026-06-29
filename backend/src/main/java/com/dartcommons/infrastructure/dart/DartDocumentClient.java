package com.dartcommons.infrastructure.dart;

import com.dartcommons.shared.util.HostWhitelist;
import com.dartcommons.shared.util.SecretMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/*
 * [목적] DART document.xml API를 호출해 공시 원문 zip을 받아오고, 내부 XML/HTML 바이트를 반환.
 *       DartDocumentParser가 이 바이트를 평문으로 변환한다.
 * [이유] disclosure 도메인이 DART HTTP 세부사항에 의존하지 않도록 infrastructure/dart에 격리(CLAUDE.md §3-2).
 *       document.xml 응답은 zip이므로 매직넘버 검증 + ZipInputStream 추출 — DartCorpCodeClient 패턴 답습.
 *       @Retryable은 네트워크 오류(RestClientException)만 재시도. noRetryFor=DartApiException으로 영구 실패는
 *       재시도 없이 즉시 전파 — 상속 계층 리팩터링 후에도 DART 할당량 낭비 방지(HIGH-5 fix).
 * [사이드 임팩트] fetchDocumentBytes()는 DisclosureContentService에서 트랜잭션 외부에서 호출됨(HIGH-2 fix).
 *               DART 일일 호출 한도에 포함 — DisclosureContentBackfillService가 throttle 관리.
 *               MAX_ZIP_ENTRY_BYTES(10MB) 초과 항목은 DartApiException(TOO_LARGE)으로 거부.
 * [수정 시 고려사항] DART 에러 응답이 JSON으로 오는 경우(status=020 등) zip 매직넘버 검증이 DartApiException으로 변환.
 *                  zip 내 파일이 여럿일 경우 첫 비디렉터리 항목만 사용 — 첨부파일이 먼저 오면 본문 누락 가능.
 *                  추후 rcept_no 기반 파일명 매칭(예: entry.getName().startsWith(rceptNo))으로 강화 가능.
 */
@Component
public class DartDocumentClient {

    private static final Logger log = LoggerFactory.getLogger(DartDocumentClient.class);

    private final RestClient restClient;
    private final DartApiProperties props;

    public DartDocumentClient(DartApiProperties props) {
        HostWhitelist.verify(props.baseUrl(), "DartDocumentClient");
        this.props = props;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMs()))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        // document.xml zip은 list.json보다 대용량 — 별도 read timeout 사용
        factory.setReadTimeout(Duration.ofMillis(props.documentTimeoutMs()));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .defaultStatusHandler(
                        status -> status.is4xxClientError() || status.is5xxServerError(),
                        (req, res) -> {
                            throw new RestClientException("DART document HTTP error: " + res.getStatusCode());
                        })
                .build();
    }

    /**
     * DART document.xml 원문을 zip으로 받아 내부 XML/HTML 바이트를 반환.
     *
     * @param rceptNo DART 접수번호(14자리)
     * @return 공시 원문 XML/HTML 바이트. DartDocumentParser 입력으로 사용.
     * @throws RestClientException 네트워크/HTTP 오류 — @Retryable 재시도 대상(transient).
     * @throws DartApiException    문서 없음·zip 매직넘버 불일치·zip 파싱 실패 — 영구 실패(no retry).
     */
    // zip 항목 크기 상한 — 10MB 초과 시 DartApiException(TOO_LARGE)로 거부(HIGH-4 fix)
    private static final int MAX_ZIP_ENTRY_BYTES = 10 * 1024 * 1024;

    @Retryable(
            retryFor = RestClientException.class,
            noRetryFor = DartApiException.class,   // 영구 실패는 재시도 없이 즉시 전파(HIGH-5 fix)
            maxAttempts = 3,
            backoff = @Backoff(delay = 2000, multiplier = 2.0, maxDelay = 30_000)
    )
    public byte[] fetchDocumentBytes(String rceptNo) {
        log.debug("DART document.xml fetch: rceptNo={}", rceptNo);
        byte[] zipBytes;
        try {
            zipBytes = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/document.xml")
                            .queryParam("crtfc_key", props.apiKey())
                            .queryParam("rcept_no", rceptNo)
                            .build())
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientException e) {
            // 예외 메시지에 URL 쿼리스트링(crtfc_key=...) 포함 가능 — 마스킹 후 원인 체인 보존(L3 fix)
            throw new RestClientException(SecretMasker.mask(e.getMessage()), e);
        }

        if (zipBytes == null || zipBytes.length == 0) {
            throw new DartApiException("EMPTY", "document.xml empty response: rceptNo=" + rceptNo);
        }
        // DART 에러 응답은 JSON으로 옴 — zip 매직넘버(PK=0x50 0x4B) 확인
        if (zipBytes.length < 4 || zipBytes[0] != 0x50 || zipBytes[1] != 0x4B) {
            String preview = new String(zipBytes, 0, Math.min(200, zipBytes.length), StandardCharsets.UTF_8);
            throw new DartApiException("INVALID",
                    "document.xml not a zip for rceptNo=" + rceptNo + ": " + SecretMasker.mask(preview));
        }

        return extractFromZip(zipBytes, rceptNo);
    }

    private byte[] extractFromZip(byte[] zipBytes, String rceptNo) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                // HIGH-4 fix: zip 헤더 크기 선점 검증 (getSize() == -1이면 미지정 → 실제 읽기로 검증)
                if (entry.getSize() > MAX_ZIP_ENTRY_BYTES) {
                    throw new DartApiException("TOO_LARGE",
                            "zip entry too large: " + entry.getSize() + " bytes for rceptNo=" + rceptNo);
                }
                // MAX+1 바이트 읽어서 초과 시 탐지 (헤더 크기 0/-1 인 경우 포함)
                byte[] content = zis.readNBytes(MAX_ZIP_ENTRY_BYTES + 1);
                if (content.length > MAX_ZIP_ENTRY_BYTES) {
                    throw new DartApiException("TOO_LARGE",
                            "zip entry exceeds " + MAX_ZIP_ENTRY_BYTES + " bytes for rceptNo=" + rceptNo);
                }
                // MEDIUM-3 fix: ZipEntry 이름 로그 주입 방지
                String safeEntryName = entry.getName().replaceAll("[\\r\\n\\t]", "_");
                log.debug("DART document extracted: rceptNo={}, entry={}, bytes={}",
                        rceptNo, safeEntryName, content.length);
                return content;
            }
        } catch (IOException e) {
            throw new DartApiException("PARSE",
                    "document.xml zip extraction failed for rceptNo=" + rceptNo + ": " + e.getMessage());
        }
        throw new DartApiException("EMPTY",
                "document.xml zip has no entries for rceptNo=" + rceptNo);
    }
}
