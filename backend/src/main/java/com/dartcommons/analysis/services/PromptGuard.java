package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.Stage2Output;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/*
 * [목적] LLM 응답의 자본시장법 + LLM 환각 가드 후처리 — Stage 2 출력이 사용자에게 닿기 전 sanitize.
 *       analysis-stage2-llm Spec 결정 5: summary 240자(3줄 × 80자) cap + 금지 키워드 강제 is_withheld.
 * [이유] CLAUDE.md §7 / 통합기획서 §11.1 — "매수/매도 추천", "꼭 사세요", "수익 보장" 표현은 자본시장법
 *       자문업 등록 의무 위반 가능. 프롬프트 가드(L1)만으로는 LLM이 출력할 수 있어 응답 후처리(L2) 이중 방어.
 * [사이드 임팩트] 금지 키워드 매칭 시 신뢰도와 무관하게 withheld=true로 강제 — 잘못된 분류로 인한 손실 0순위 회피.
 *               summary 글자수 cap은 LLM 횡설수설 방지(qwen3:4b smoke test에서 3줄 → 5줄 횡설 관측됨).
 *               cap 이후 마침표 부근에서 자르려 시도 — 문장 중간 절단 최소화.
 * [수정 시 고려사항] FORBIDDEN_PATTERNS 추가는 운영 피드백 기반으로만(과보수 시 정상 응답까지 보류).
 *                  법무 검수 통과 표현은 보류 대상에서 제외(현재 키워드는 자본시장법 명시 사례).
 *                  summary 길이 한계 변경 시 Spec §6.4 + DB 컬럼(TEXT) 영향 없음.
 */
@Component
public class PromptGuard {

    /** Spec 결정 5: 240자(3줄 × 80자) — qwen3:4b 횡설수설 방지. */
    public static final int SUMMARY_MAX_CHARS = 240;

    /** Wave 2: key_points/요인 리스트 항목 수 상한 — 프롬프트 가이드(1~4개)에 여유. LLM 환각·주입 시 무한 팽창 차단. */
    public static final int MAX_LIST_ITEMS = 8;
    /** Wave 2: 리스트 항목 1개 글자 수 상한 — summary(240) 규율과 정합. 초과 시 절단 후 "…". */
    public static final int MAX_ITEM_CHARS = 200;

    /*
     * 자본시장법 표현 금지 키워드 (통합기획서 §11.1).
     * 단순 contains — 부정문/맥락 구분 못함. 매칭 시 보류 처리(과보수 OK).
     */
    private static final List<Pattern> FORBIDDEN_PATTERNS = List.of(
            Pattern.compile("추천"),
            Pattern.compile("매수"),
            Pattern.compile("매도"),
            Pattern.compile("사세요"),
            Pattern.compile("파세요"),
            Pattern.compile("수익\\s*보장"),
            Pattern.compile("손실\\s*없"),
            Pattern.compile("확정\\s*수익"),
            Pattern.compile("꼭\\s*사")
    );

    /**
     * LLM 원응답에 가드 적용 — sanitize된 새 Stage2Output 반환.
     * 원본은 변경하지 않음(record 불변).
     *
     * 적용 규칙:
     * 1. summary가 240자 초과 → 240자 이하의 마지막 마침표/물음표/느낌표 지점에서 자름.
     *    적절한 종결 부호가 없으면 240자에서 강제 절단 후 "…" 부착.
     * 2. summary에 금지 키워드 매칭 → withheld=true 강제 (sentiment/confidence는 보존, 화면이 "판단 보류" 표시).
     */
    public GuardResult sanitize(Stage2Output raw, double confidenceThreshold) {
        String summary = raw.summary() == null ? "" : raw.summary();
        // Wave 2: key_points/호재·악재 요인도 사용자 노출 텍스트 → 자본시장법 금지 키워드 동일 스캔(§11.1).
        boolean forbiddenHit = containsForbidden(summary)
                || anyForbidden(raw.keyPoints())
                || anyForbidden(raw.positiveFactors())
                || anyForbidden(raw.negativeFactors());
        String capped = capLength(summary);

        boolean withheldByThreshold = raw.confidence().doubleValue() < confidenceThreshold;
        boolean withheld = forbiddenHit || withheldByThreshold;

        // 리스트도 항목 수·길이 캡 — LLM 환각/프롬프트 주입에 의한 JSONB 팽창·전 티어 응답 비대화 차단(summary 캡과 정합).
        Stage2Output sanitized = new Stage2Output(raw.sentiment(), raw.confidence(), capped,
                capList(raw.keyPoints()), capList(raw.positiveFactors()), capList(raw.negativeFactors()));
        return new GuardResult(sanitized, withheld, forbiddenHit, withheldByThreshold);
    }

    /** 리스트 항목 수(MAX_LIST_ITEMS)와 항목별 길이(MAX_ITEM_CHARS)를 캡. null 안전. */
    private static List<String> capList(List<String> items) {
        if (items == null || items.isEmpty()) return items;
        return items.stream()
                .limit(MAX_LIST_ITEMS)
                .map(s -> s != null && s.length() > MAX_ITEM_CHARS ? s.substring(0, MAX_ITEM_CHARS) + "…" : s)
                .toList();
    }

    private static boolean containsForbidden(String text) {
        if (text == null) return false;
        for (Pattern p : FORBIDDEN_PATTERNS) {
            if (p.matcher(text).find()) return true;
        }
        return false;
    }

    /** 리스트 항목 중 하나라도 금지 키워드 매칭 시 true. null 안전. */
    private static boolean anyForbidden(List<String> items) {
        if (items == null) return false;
        for (String item : items) {
            if (containsForbidden(item)) return true;
        }
        return false;
    }

    private static String capLength(String text) {
        if (text.length() <= SUMMARY_MAX_CHARS) return text;
        // 240자 이하에서 마지막 종결 부호 찾기 (한글 마침표/온점/물음표/느낌표).
        String head = text.substring(0, SUMMARY_MAX_CHARS);
        int lastStop = lastIndexOfAny(head, ".!?。");
        if (lastStop > SUMMARY_MAX_CHARS / 2) {
            return head.substring(0, lastStop + 1);
        }
        return head + "…";
    }

    private static int lastIndexOfAny(String text, String chars) {
        int best = -1;
        for (int i = 0; i < chars.length(); i++) {
            int idx = text.lastIndexOf(chars.charAt(i));
            if (idx > best) best = idx;
        }
        return best;
    }

    /**
     * sanitize 결과 + 부가 정보.
     * forbiddenHit/withheldByThreshold는 운영 로그·통계 분리용.
     */
    public record GuardResult(
            Stage2Output sanitized,
            boolean withheld,
            boolean forbiddenHit,
            boolean withheldByThreshold
    ) {
    }
}
