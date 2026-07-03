package com.dartcommons.infrastructure.dart;

import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/*
 * [목적] DART document.xml 바이트(HTML/XML 마크업)를 임베딩·분석에 적합한 평문 텍스트로 변환.
 *       ① charset 프로빙 디코딩(BOM→UTF-8 strict→선언→MS949→EUC-KR) ② 태그 제거 ③ 표 직렬화 ④ HTML 엔티티 ⑤ 공백 정규화.
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

    /**
     * UTF-8 strict 실패 시 lenient 결과를 UTF-8로 수용하는 치환문자 비율 상한.
     * 진짜 UTF-8(잡바이트 소수)은 ~0.5% 이하, EUC-KR을 UTF-8로 오독하면 ~30~50% → 명확히 분리됨.
     * content-text-charset-mojibake 2차 수정: strict all-or-nothing이 소수 비적합 바이트에 문서 전체를
     * EUC-KR로 폴백시켜 전면 mojibake 유발 → 이 임계로 UTF-8 우선 유지.
     */
    private static final double UTF8_LENIENT_MAX_REPLACEMENT_RATIO = 0.02;

    // ReDoS 방어: 5MB 초과 입력은 절사 후 처리 (DART 공시 원문은 통상 수십~수백KB)
    private static final int MAX_RAW_BYTES = 5 * 1024 * 1024;

    // EUC-KR 지원 여부 JRE별 차이 — 초기화 시 확인 후 폴백
    private static final Charset DEFAULT_CHARSET;
    /** strict 프로빙 후보(순서 중요) — UTF-8 우선(자기검증), 실패 시 한국어 레거시 MS949(EUC-KR superset). */
    private static final List<Charset> PROBE_CHARSETS;
    static {
        Charset eucKr;
        try {
            eucKr = Charset.forName("EUC-KR");
        } catch (Exception e) {
            eucKr = StandardCharsets.UTF_8;
        }
        DEFAULT_CHARSET = eucKr;
        Charset ms949;
        try {
            ms949 = Charset.forName("MS949");  // = windows-949, CP949 — EUC-KR 상위집합(한글 완성형 전부 커버)
        } catch (Exception e) {
            ms949 = eucKr;
        }
        PROBE_CHARSETS = List.of(StandardCharsets.UTF_8, ms949);
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
     * charset 프로빙(BOM → UTF-8 strict → 선언 → MS949 strict → EUC-KR lenient)으로 mojibake 방지.
     * MAX_RAW_BYTES(5MB) 초과 입력은 절사 — 정규식 처리 시간 상한 보장.
     */
    public String extractText(byte[] rawBytes) {
        if (rawBytes == null || rawBytes.length == 0) return "";
        if (rawBytes.length > MAX_RAW_BYTES) {
            rawBytes = Arrays.copyOf(rawBytes, MAX_RAW_BYTES);
        }
        String markup = decodeBytes(rawBytes);
        return processMarkup(markup);
    }

    /*
     * [목적] raw 바이트를 올바른 charset으로 디코딩 — content-text-charset-mojibake Spec 카드 #1.
     * [이유] 기존 `new String(bytes, charset)`는 잘못된 charset이어도 예외 없이 조용히 mojibake('�' 또는 valid-but-wrong)
     *       생성 → DB 저장 → LLM이 깨진 숫자를 읽고 환각(94128 "448조원"). strict 디코딩(REPORT)으로 실패를 감지해
     *       올바른 charset을 선택한다.
     * [사이드 임팩트] UTF-8은 바이트 구조가 자기검증(연속 바이트 규칙) → strict 성공 시 거의 확실히 UTF-8(오탐 ≈0).
     *               한국어 레거시(EUC-KR/MS949) 문서는 UTF-8 strict 실패 → 다음 후보로. 최후엔 EUC-KR lenient(무예외).
     * [수정 시 고려사항] 선언 charset이 UTF-8이 아니면서 strict 성공 시 신뢰(카드 #1). 짧은/모호한 바이트열은
     *                  여전히 오판 가능 — 완전 무결 보장 불가(Spec 리스크). 후보 추가는 PROBE_CHARSETS만 수정.
     */
    private String decodeBytes(byte[] bytes) {
        // 1) BOM 우선 — 명시적 인코딩 신호(BOM 바이트는 제거)
        BomResult bom = detectBom(bytes);
        if (bom != null) {
            return new String(bytes, bom.offset(), bytes.length - bom.offset(), bom.charset());
        }
        // 2) UTF-8 strict — 자기검증이라 성공하면 UTF-8로 확정(오탐 ≈0)
        Optional<String> utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8.isPresent()) return utf8.get();
        // 3) UTF-8 strict 실패지만 치환율이 낮으면 UTF-8로 확정(소수 잡바이트만 손상) — all-or-nothing 회피.
        //    실측(content-text-charset-mojibake 2차): 실제 DART 문서에 Win-1252 문장부호 등 UTF-8 비적합 바이트가
        //    섞여 있어 strict가 문서 전체를 포기 → EUC-KR 폴백 → 전면 mojibake. 대다수가 UTF-8이면 소수 �만 감수.
        String utf8Lenient = new String(bytes, StandardCharsets.UTF_8);  // REPLACE — 잘못된 바이트만 U+FFFD
        if (replacementRatio(utf8Lenient) < UTF8_LENIENT_MAX_REPLACEMENT_RATIO) {
            return utf8Lenient;
        }
        // 4) 선언된 charset(UTF-8 외)이 strict 성공하면 신뢰
        Charset declared = detectDeclaredCharset(bytes);
        if (declared != null && !StandardCharsets.UTF_8.equals(declared)) {
            Optional<String> d = decodeStrict(bytes, declared);
            if (d.isPresent()) return d.get();
        }
        // 5) 한국어 레거시 후보 strict 프로빙 (MS949 등)
        for (Charset cs : PROBE_CHARSETS) {
            if (StandardCharsets.UTF_8.equals(cs)) continue;  // 이미 시도
            Optional<String> s = decodeStrict(bytes, cs);
            if (s.isPresent()) return s.get();
        }
        // 6) 최후 — 어느 것도 strict 통과 못하면 EUC-KR lenient(무예외, 최선 추정)
        return new String(bytes, DEFAULT_CHARSET);
    }

    /** 문자열 내 U+FFFD(치환문자) 비율 — 0이면 손상 없음. 빈 문자열은 0. */
    private static double replacementRatio(String s) {
        if (s.isEmpty()) return 0.0;
        int repl = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '�') repl++;
        }
        return (double) repl / s.length();
    }

    /** malformed/unmappable를 REPORT로 감지하는 strict 디코딩 — 실패 시 Optional.empty(잘못된 charset). */
    private static Optional<String> decodeStrict(byte[] bytes, Charset cs) {
        CharsetDecoder decoder = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return Optional.of(decoder.decode(ByteBuffer.wrap(bytes)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }

    /** BOM 감지 — 있으면 charset + BOM 바이트 길이(offset) 반환. 없으면 null. */
    private static BomResult detectBom(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new BomResult(StandardCharsets.UTF_8, 3);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new BomResult(StandardCharsets.UTF_16LE, 2);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new BomResult(StandardCharsets.UTF_16BE, 2);
        }
        return null;
    }

    private record BomResult(Charset charset, int offset) {}

    /** XML 선언의 encoding 속성에서 charset 추출 — 없거나 미지원이면 null. */
    private static Charset detectDeclaredCharset(byte[] bytes) {
        // XML 선언은 ASCII 범위이므로 ISO-8859-1로 안전하게 읽을 수 있음
        String header = new String(bytes, 0, Math.min(MAX_DECLARATION_SCAN_BYTES, bytes.length),
                StandardCharsets.ISO_8859_1);
        Matcher m = ENCODING_DECL.matcher(header);
        if (m.find()) {
            try {
                return Charset.forName(m.group(1).trim());
            } catch (Exception ignored) {
                // 알 수 없는 인코딩 — null 반환(프로빙에 위임)
            }
        }
        return null;
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
