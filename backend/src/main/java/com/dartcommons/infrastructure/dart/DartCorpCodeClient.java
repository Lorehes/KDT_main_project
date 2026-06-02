package com.dartcommons.infrastructure.dart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/*
 * [목적] DART corpCode.xml zip을 다운로드해 (corp_code, corp_name, stock_code) 사전을 반환.
 *       StockMasterSyncJob이 분기 1회 호출 — 상장사 약 3000개 매핑 갱신.
 * [이유] disclosure 도메인이 받는 corp_code↔stock_code 매핑의 SSOT.
 *       응답이 zip이라 RestClient 바이트 수신 → 메모리 unzip → StAX 스트리밍 파싱
 *       (DOM 파싱 시 약 10MB XML 전체 로드 — StAX로 메모리 절감).
 * [사이드 임팩트] 분기 1회 호출이지만 응답 zip ~1MB, 압축해제 ~10MB — heap 영향 미미.
 *               DART rate limit(일 20,000)에 거의 영향 없음(스케줄당 1호출).
 *               에러 시 DartApiException throw — 호출자(StockMasterSyncJob)가 catch.
 * [수정 시 고려사항] DART 응답이 JSON 에러로 올 가능성(키 오류 등) — Content-Type 검사 필요.
 *                  StAX 파서는 한 번에 한 항목씩 — 메모리 폭증 회피.
 *                  본문 추출(document.xml) 클라이언트는 후속 Spec.
 */
@Component
public class DartCorpCodeClient {

    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeClient.class);

    private final RestClient restClient;
    private final DartApiProperties props;

    public DartCorpCodeClient(DartApiProperties props) {
        this.props = props;
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(props.timeoutMs()))
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        // corpCode.xml zip은 ~1MB — 일반 타임아웃의 3배 여유
        factory.setReadTimeout(Duration.ofMillis(props.timeoutMs() * 3L));
        this.restClient = RestClient.builder()
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * corpCode.xml zip을 다운로드하고 상장사(stock_code 6자리)만 추출해 반환한다.
     * 비상장 항목(stock_code 비어있음)은 skip.
     */
    public List<CorpCode> fetchAllListed() {
        log.info("DART corpCode.xml fetch start");
        byte[] zipBytes = restClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/corpCode.xml")
                        .queryParam("crtfc_key", props.apiKey())
                        .build())
                .retrieve()
                .body(byte[].class);

        if (zipBytes == null || zipBytes.length == 0) {
            throw new DartApiException("EMPTY", "corpCode.xml empty response");
        }
        // DART 에러 응답은 JSON으로 옴 — zip 매직넘버 확인
        if (zipBytes.length < 4 || zipBytes[0] != 0x50 || zipBytes[1] != 0x4B) {
            String preview = new String(zipBytes, 0, Math.min(200, zipBytes.length));
            throw new DartApiException("INVALID", "corpCode.xml not a zip: " + preview);
        }

        List<CorpCode> result = new ArrayList<>(3500);
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.getName().equalsIgnoreCase("CORPCODE.xml")) continue;
                parseCorpCodeXml(zis, result);
                break;
            }
        } catch (Exception e) {
            throw new DartApiException("PARSE", "corpCode.xml parse error: " + e.getMessage());
        }

        log.info("DART corpCode.xml fetch done: listed={}", result.size());
        return result;
    }

    /**
     * StAX 스트리밍 파서 — 항목별로 처리해 메모리 폭증 방지.
     * 상장사(stock_code 길이 6)만 result에 추가.
     */
    private void parseCorpCodeXml(java.io.InputStream in, List<CorpCode> result) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);

        XMLStreamReader reader = factory.createXMLStreamReader(in);
        String corpCode = null, corpName = null, stockCode = null;
        String currentTag = null;

        while (reader.hasNext()) {
            int event = reader.next();
            switch (event) {
                case XMLStreamConstants.START_ELEMENT -> {
                    currentTag = reader.getLocalName();
                    if ("list".equals(currentTag)) {
                        corpCode = corpName = stockCode = null;
                    }
                }
                case XMLStreamConstants.CHARACTERS -> {
                    if (currentTag == null) break;
                    String text = reader.getText();
                    switch (currentTag) {
                        case "corp_code" -> corpCode = appendText(corpCode, text);
                        case "corp_name" -> corpName = appendText(corpName, text);
                        case "stock_code" -> stockCode = appendText(stockCode, text);
                    }
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if ("list".equals(reader.getLocalName())) {
                        String trimmed = stockCode == null ? "" : stockCode.trim();
                        if (trimmed.length() == 6 && corpCode != null && corpName != null) {
                            result.add(new CorpCode(corpCode.trim(), corpName.trim(), trimmed));
                        }
                    }
                    currentTag = null;
                }
            }
        }
        reader.close();
    }

    private static String appendText(String prev, String add) {
        return prev == null ? add : prev + add;
    }

    /**
     * DART 고유번호 매핑 — stock_code가 6자리인 상장사만.
     */
    public record CorpCode(String corpCode, String corpName, String stockCode) {
    }
}
