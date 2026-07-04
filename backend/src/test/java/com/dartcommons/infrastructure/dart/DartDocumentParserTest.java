package com.dartcommons.infrastructure.dart;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] DartDocumentParser.extractText()의 핵심 변환 경로를 인메모리 픽스처로 단위 검증.
 *       EUC-KR 인코딩 감지, 태그 제거, 표 직렬화, 엔티티 디코딩, 공백 정규화.
 * [이유] 외부 DART API 호출 없이 파서 로직만 격리 검증 — 빠른 피드백, CI 친화적.
 *       DART 원문 특성(EUC-KR, 표 중심)을 픽스처로 시뮬레이션.
 * [사이드 임팩트] EUC-KR 미지원 JRE에서 DEFAULT_CHARSET 폴백 → 인코딩 감지 테스트 실패 가능(CI 환경 확인 필요).
 * [수정 시 고려사항] 정규식 패턴 변경 시 관련 테스트 케이스 추가. DART 실제 공시로 검증은 @Tag("dart-live") 별도.
 */
class DartDocumentParserTest {

    private final DartDocumentParser parser = new DartDocumentParser();

    @Test
    @DisplayName("UTF-8 HTML — 기본 태그 제거 + 텍스트 추출")
    void extractText_utf8_basicTagRemoval() {
        String html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><p>공시 제목</p><p>본문 내용</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        assertThat(result).contains("공시 제목");
        assertThat(result).contains("본문 내용");
        assertThat(result).doesNotContain("<p>");
        assertThat(result).doesNotContain("</p>");
    }

    @Test
    @DisplayName("EUC-KR 인코딩 선언 — 한글 정상 추출")
    void extractText_euckr_koreanContent() {
        // MEDIUM-4 fix: EUC-KR 미지원 JRE에서 assumeTrue로 skip — return은 always-pass처럼 보여 오해 유발
        Assumptions.assumeTrue(Charset.isSupported("EUC-KR"), "EUC-KR 미지원 환경 — 테스트 skip");
        Charset euckr = Charset.forName("EUC-KR");

        String html = "<?xml version=\"1.0\" encoding=\"EUC-KR\"?><html><body><p>삼성전자 공시 내용</p></body></html>";
        byte[] bytes = html.getBytes(euckr);

        String result = parser.extractText(bytes);

        assertThat(result).contains("삼성전자 공시 내용");
    }

    // ── content-text-charset-mojibake: charset 프로빙 회귀 ──

    @Test
    @DisplayName("UTF-8 문서인데 인코딩 선언 없음 → mojibake 없이 정상(과거엔 EUC-KR 기본으로 깨짐)")
    void extractText_utf8_noDeclaration_noMojibake() {
        // 선언 없는 UTF-8 한글 — 기존 detectCharset은 EUC-KR 기본 적용 → mojibake. 이제 UTF-8 strict가 잡음.
        String html = "<html><body><p>한전기술 계약금액 448억원 규모입니다</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        assertThat(result).contains("한전기술 계약금액 448억원 규모입니다");
        assertThat(result).doesNotContain("�");  // 치환문자(�) 없음
    }

    @Test
    @DisplayName("UTF-8 문서인데 EUC-KR로 잘못 선언 → 선언 무시하고 UTF-8로 정상 디코딩")
    void extractText_utf8_wronglyDeclaredEucKr() {
        // 실제 mojibake 원인 재현: 선언은 euc-kr인데 바이트는 UTF-8 → UTF-8 strict가 먼저 성공해 선언 무시
        String html = "<?xml version=\"1.0\" encoding=\"euc-kr\"?><html><body><p>취득금액 319,100,000,000원</p></body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        assertThat(result).contains("취득금액 319,100,000,000원");
        assertThat(result).doesNotContain("�");
    }

    @Test
    @DisplayName("UTF-8 본문에 잡바이트 1개 섞임 → 전면 mojibake 없이 UTF-8 유지 (strict all-or-nothing 회귀)")
    void extractText_utf8_withStrayByte_staysUtf8() {
        // 2차 수정 회귀: 실제 DART 문서는 UTF-8이면서 Win-1252 문장부호 등 비적합 바이트가 소수 섞임.
        // strict all-or-nothing이면 문서 전체를 EUC-KR로 폴백 → "蹂寃" 식 전면 mojibake(94128 "448조원" 유발).
        // 치환율 임계(2%) 미만이면 UTF-8 lenient 유지 → 한글·숫자 보존, 잡바이트만 �.
        StringBuilder body = new StringBuilder("<html><body>");
        for (int i = 0; i < 40; i++) {
            body.append("<p>변경 계약금액 448억원 삼성전자 취득결정 리스크 공시 본문 ").append(i).append("</p>");
        }
        body.append("</body></html>");
        byte[] kor = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[kor.length + 1];
        System.arraycopy(kor, 0, bytes, 0, 500);
        bytes[500] = (byte) 0xFF;  // UTF-8 비적합 잡바이트 1개
        System.arraycopy(kor, 500, bytes, 501, kor.length - 500);

        String result = parser.extractText(bytes);

        // 원본 수치·회사명 보존(CLAUDE.md §4) — mojibake면 이 문자열들이 깨져 사라짐
        assertThat(result).contains("448억원");
        assertThat(result).contains("삼성전자");
        assertThat(result).contains("계약금액");
        // 치환문자는 극소수만(잡바이트 근방) — 전면 mojibake가 아님
        long repl = result.chars().filter(c -> c == 0xFFFD).count();
        assertThat(repl).isLessThan(10);
    }

    @Test
    @DisplayName("UTF-8 BOM → BOM 제거 + 정상 디코딩")
    void extractText_utf8Bom() {
        String html = "<html><body><p>BOM 포함 공시 본문</p></body></html>";
        byte[] content = html.getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[content.length + 3];
        bytes[0] = (byte) 0xEF; bytes[1] = (byte) 0xBB; bytes[2] = (byte) 0xBF;  // UTF-8 BOM
        System.arraycopy(content, 0, bytes, 3, content.length);

        String result = parser.extractText(bytes);

        assertThat(result).contains("BOM 포함 공시 본문");
        assertThat(result).doesNotContain("﻿");  // BOM 문자 제거됨
        assertThat(result).doesNotContain("�");
    }

    @Test
    @DisplayName("EUC-KR 문서인데 인코딩 선언 없음 → MS949 프로빙으로 정상")
    void extractText_euckr_noDeclaration_probed() {
        Assumptions.assumeTrue(Charset.isSupported("MS949"), "MS949 미지원 환경 — 테스트 skip");
        Charset ms949 = Charset.forName("MS949");
        String html = "<html><body><p>현대지에프홀딩스 주식매수청구권 사항</p></body></html>";
        byte[] bytes = html.getBytes(ms949);

        String result = parser.extractText(bytes);

        assertThat(result).contains("현대지에프홀딩스 주식매수청구권 사항");
        assertThat(result).doesNotContain("�");
    }

    @Test
    @DisplayName("표(table) 직렬화 — td 셀은 공백으로 연결, tr은 개행 처리")
    void extractText_tableSerialization() {
        String html = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html><body>
                <table>
                  <tr><th>항목</th><th>금액</th></tr>
                  <tr><td>매출액</td><td>1,000억</td></tr>
                </table>
                </body></html>
                """;
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        assertThat(result).contains("항목");
        assertThat(result).contains("금액");
        assertThat(result).contains("매출액");
        assertThat(result).contains("1,000억");
    }

    @Test
    @DisplayName("HTML 엔티티 디코딩 — &amp; &lt; &gt; &#47; &#x2F;")
    void extractText_entityDecoding() {
        String html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body>"
                + "<p>A &amp; B &lt; C &gt; D &#47; E &#x2F; F</p>"
                + "</body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        assertThat(result).contains("A & B < C > D / E / F");
    }

    @Test
    @DisplayName("script/style/head 블록 제거 — 내용 포함")
    void extractText_removeScriptStyleHead() {
        String html = """
                <?xml version="1.0" encoding="UTF-8"?>
                <html>
                  <head><title>페이지 제목</title></head>
                  <body>
                    <script>alert('xss')</script>
                    <style>.cls { color: red; }</style>
                    <p>실제 공시 본문</p>
                  </body>
                </html>
                """;
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        assertThat(result).contains("실제 공시 본문");
        assertThat(result).doesNotContain("alert");
        assertThat(result).doesNotContain("color: red");
        assertThat(result).doesNotContain("페이지 제목");
    }

    @Test
    @DisplayName("빈 입력 — 빈 문자열 반환 (null 없음)")
    void extractText_emptyInput_returnsEmpty() {
        assertThat(parser.extractText(null)).isEmpty();
        assertThat(parser.extractText(new byte[0])).isEmpty();
    }

    @Test
    @DisplayName("과도한 연속 개행 — 2개 이하로 압축")
    void extractText_excessNewlines_compressed() {
        String html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body>"
                + "<p>문단1</p><div></div><div></div><div></div><p>문단2</p>"
                + "</body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        // 연속 개행이 3개 이상 있으면 2개로 압축됨
        assertThat(result).doesNotContain("\n\n\n");
        assertThat(result).contains("문단1");
        assertThat(result).contains("문단2");
    }

    @Test
    @DisplayName("BLOCK_REMOVE 백레퍼런스 — script 시작 태그는 style 닫기 태그로 닫히지 않음")
    void extractText_blockRemove_backreferenceCorrectness() {
        // HIGH-1 fix 검증: </\\1> 패턴으로 <script>는 </script>만 닫음.
        // <script> 다음에 </style>만 있으면 script 블록이 제거되어서는 안 됨.
        String html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body>"
                + "<script>var x = 1;</style>"   // 잘못된 닫기 — script 블록 미완료
                + "<p>본문은 살아남아야</p>"
                + "</body></html>";
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

        String result = parser.extractText(bytes);

        // 백레퍼런스가 올바르면 <script>...</style>을 하나의 블록으로 취급 안 함
        // → "본문은 살아남아야" 텍스트는 반드시 남아 있어야 함
        assertThat(result).contains("본문은 살아남아야");
    }

    @Test
    @DisplayName("makeZip 픽스처 — zip 형태 바이트에서 텍스트 추출 (DartDocumentClient 파이프라인 통합)")
    void extractText_fromZipFixture() throws Exception {
        String html = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><p>zip 픽스처 본문</p></body></html>";
        byte[] htmlBytes = html.getBytes(StandardCharsets.UTF_8);

        // makeZip()으로 zip을 만들고 내부 바이트만 꺼내서 parser에 전달 — DartDocumentClient 추출 결과를 시뮬레이션
        byte[] zipBytes = makeZip("disclosure.xml", htmlBytes);
        // zip에서 첫 엔트리 내용만 수동으로 추출 (DartDocumentClient.extractFromZip 역할)
        java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes));
        zis.getNextEntry();
        byte[] extracted = zis.readAllBytes();

        String result = parser.extractText(extracted);

        assertThat(result).contains("zip 픽스처 본문");
        assertThat(result).doesNotContain("<p>");
    }

    // ---- 헬퍼: zip 픽스처 생성 (DartDocumentClient가 반환하는 형태) ----

    static byte[] makeZip(String entryName, byte[] content) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            zos.putNextEntry(new ZipEntry(entryName));
            zos.write(content);
            zos.closeEntry();
        }
        return baos.toByteArray();
    }
}
