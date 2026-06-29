package com.dartcommons.infrastructure.dart;

import org.springframework.stereotype.Component;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/*
 * [목적] DART document.xml 바이트(HTML/XML 마크업)를 임베딩·분석에 적합한 평문 텍스트로 변환.
 *       ① EUC-KR/MS949 인코딩 감지 ② 태그 제거 ③ 표 직렬화 ④ HTML 엔티티 디코딩 ⑤ 공백 정규화.
 * [이유] DART 원문은 독자 HTML 마크업(XML 선언 + HTML body). DOM/SAX 파서는 비정규 HTML에 취약.
 *       정규식 기반 추출이 실용적 — DART 원문은 스크립트 복잡도 낮고 구조 단순.
 *       LLM 미사용 원칙(CLAUDE.md §4): 원본 수치·날짜·회사명은 변형 없이 그대로 포함.
 * [사이드 임팩트] 추출 품질은 DART 마크업 변형에 따라 달라짐 — 시범 표본 검수 필수(Spec R9).
 *               반환 텍스트는 null 없이 빈 문자열("")이 최소값. DisclosureContentService가 null 구분.
 *               EUC-KR 미지원 JRE(드묾)에서 구동 실패 가능 — DEFAULT_CHARSET 초기화 실패 시 UTF-8 폴백.
 *               MAX_RAW_BYTES(5MB)를 초과하는 입력은 자동 절사 — 극히 드문 대용량 공시에서만 발생.
 * [수정 시 고려사항] extractText()는 byte[] → String 전환 + 마크업 처리 두 책임을 가짐.
 *                  품질 개선 필요 시 Apache Tika(HTML 파서) 도입 검토 — 단 추가 의존성 비용.
 *                  ENTITY_MAP 확장: 비ASCII 엔티티(&middot;, &laquo; 등) 추가 시 맵에 항목 추가.
 *                  BLOCK_REMOVE 패턴은 \\1 역참조를 사용 — 여는 태그와 닫는 태그가 동일한 요소임을 강제.
 */
@Component
public class DartDocumentParser {

    private static final int MAX_DECLARATION_SCAN_BYTES = 300;

    // ReDoS 방어: 5MB 초과 입력은 절사 후 처리 (DART 공시 원문은 통상 수십~수백KB)
    private static final int MAX_RAW_BYTES = 5 * 1024 * 1024;

    // EUC-KR 지원 여부 JRE별 차이 — 초기화 시 확인 후 폴백
    private static final Charset DEFAULT_CHARSET;
    static {
        Charset cs;
        try {
            cs = Charset.forName("EUC-KR");
        } catch (Exception e) {
            cs = StandardCharsets.UTF_8;
        }
        DEFAULT_CHARSET = cs;
    }

    // script/style/head 블록 전체 제거 (내용 포함)
    // \\1 역참조: 닫는 태그가 여는 태그와 동일한 요소임을 강제 — <script>...</style> 오매칭 방지(HIGH-1 fix)
    private static final Pattern BLOCK_REMOVE = Pattern.compile(
            "<(script|style|head)(\\s[^>]*)?>.*?</\\1>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    // 셀 끝 → 공백 (표 내용 연결)
    private static final Pattern CELL_END = Pattern.compile(
            "</(td|th)\\s*>",
            Pattern.CASE_INSENSITIVE);

    // 블록 요소 끝 / 줄바꿈 태그 → 개행
    private static final Pattern BLOCK_BREAK = Pattern.compile(
            "</(p|div|li|h[1-6]|tr|thead|tbody|tfoot|title|table)\\s*>|<br\\s*/?>",
            Pattern.CASE_INSENSITIVE);

    // 나머지 태그 전부 제거
    private static final Pattern ALL_TAGS = Pattern.compile("<[^>]+>");

    // HTML 엔티티 — 숫자형 포함
    private static final Pattern ENTITY = Pattern.compile(
            "&([a-zA-Z]{2,8}|#[0-9]{1,6}|#x[0-9a-fA-F]{1,6});");

    // XML 선언 내 encoding 속성
    private static final Pattern ENCODING_DECL = Pattern.compile(
            "encoding=[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);

    // 행 내 연속 공백(NBSP 포함)
    private static final Pattern MULTI_SPACE = Pattern.compile("[ \\t\\u00A0]+");

    // 3회 이상 연속 개행 → 2회로 압축
    private static final Pattern EXCESS_NEWLINES = Pattern.compile("\\n{3,}");

    /**
     * DART document.xml raw 바이트 → 평문 텍스트.
     * 인코딩 선언이 없으면 EUC-KR 기본 적용(DART 구 공시 특성).
     * MAX_RAW_BYTES(5MB) 초과 입력은 절사 — 정규식 처리 시간 상한 보장.
     */
    public String extractText(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) return "";
        if (rawBytes.length > MAX_RAW_BYTES) {
            rawBytes = Arrays.copyOf(rawBytes, MAX_RAW_BYTES);
        }
        Charset charset = detectCharset(rawBytes);
        String markup = new String(rawBytes, charset);
        return processMarkup(markup);
    }

    private Charset detectCharset(byte[] bytes) {
        // XML 선언은 ASCII 범위이므로 ISO-8859-1로 안전하게 읽을 수 있음
        String header = new String(bytes, 0, Math.min(MAX_DECLARATION_SCAN_BYTES, bytes.length),
                StandardCharsets.ISO_8859_1);
        Matcher m = ENCODING_DECL.matcher(header);
        if (m.find()) {
            String enc = m.group(1).trim();
            try {
                return Charset.forName(enc);
            } catch (Exception ignored) {
                // 알 수 없는 인코딩 — 기본값 사용
            }
        }
        return DEFAULT_CHARSET;
    }

    private String processMarkup(String markup) {
        // 1. script/style/head 블록 전체 제거
        String text = BLOCK_REMOVE.matcher(markup).replaceAll(" ");
        // 2. 셀 구분자 → 공백 (표 내용 연결)
        text = CELL_END.matcher(text).replaceAll(" ");
        // 3. 블록 요소 끝 → 개행
        text = BLOCK_BREAK.matcher(text).replaceAll("\n");
        // 4. 나머지 태그 제거
        text = ALL_TAGS.matcher(text).replaceAll("");
        // 5. HTML 엔티티 디코딩
        text = decodeEntities(text);
        // 6. 줄별 공백 정규화 + 빈 줄 제거
        text = Arrays.stream(text.split("\n"))
                .map(line -> MULTI_SPACE.matcher(line.trim()).replaceAll(" "))
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
        // 7. 과도한 연속 개행 압축
        text = EXCESS_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.trim();
    }

    private String decodeEntities(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        Matcher m = ENTITY.matcher(text);
        while (m.find()) {
            String entity = m.group(1);
            String replacement;
            try {
                if (entity.startsWith("#x") || entity.startsWith("#X")) {
                    int codePoint = Integer.parseInt(entity.substring(2), 16);
                    // L4 fix: 유효 범위(0~0x10FFFF) + 서로게이트 범위(0xD800~0xDFFF) 제외
                    replacement = isValidCodePoint(codePoint)
                            ? new String(Character.toChars(codePoint)) : "�";
                } else if (entity.startsWith("#")) {
                    int codePoint = Integer.parseInt(entity.substring(1));
                    replacement = isValidCodePoint(codePoint)
                            ? new String(Character.toChars(codePoint)) : "�";
                } else {
                    replacement = switch (entity.toLowerCase()) {
                        case "amp" -> "&";
                        case "lt" -> "<";
                        case "gt" -> ">";
                        case "quot" -> "\"";
                        case "apos" -> "'";
                        case "nbsp" -> " ";
                        case "middot" -> "·";
                        case "bull" -> "•";
                        case "ndash" -> "–";
                        case "mdash" -> "—";
                        default -> m.group(0); // 미지원 엔티티: 원문 유지
                    };
                }
            } catch (IllegalArgumentException e) {
                replacement = m.group(0); // 파싱 실패: 원문 유지
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static boolean isValidCodePoint(int cp) {
        return cp >= 0 && cp <= 0x10FFFF && (cp < 0xD800 || cp > 0xDFFF);
    }
}
