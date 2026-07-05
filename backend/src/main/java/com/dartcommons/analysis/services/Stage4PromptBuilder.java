package com.dartcommons.analysis.services;

import com.dartcommons.analysis.dto.PriceReactionForecast;
import com.dartcommons.analysis.dto.SimilarDisclosureItem;
import com.dartcommons.analysis.entities.AnalysisResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/*
 * [목적] Stage 4 LLM 2차 분석(최종 판단) 프롬프트 생성.
 *       Stage 2 결과 + 유사 공시 목록(최대 10건) + D+1~D+5 실측 주가 반응을 단일 프롬프트로 조립.
 * [이유] 실측 수치(회사명/날짜/등락률)를 그대로 주입하고 "제공된 수치만 사용" 강제 — LLM 환각 방지(CLAUDE.md §4).
 *       Stage 2와 동일한 자본시장법 가드 명시(통합기획서 §11.1) — expected_reaction은 방향성 정보이지 투자 권유 아님.
 * [사이드 임팩트] 유사 공시가 없거나 forecast가 없으면 해당 섹션 미포함 — Stage4Analyzer가 사전에 표본 체크.
 *               프롬프트 길이: 유사 공시 10건 × 1항목당 ~80자 + 헤더 ~800자 ≈ ~1,600자. 토큰 부담 낮음.
 * [수정 시 고려사항] 유사 공시 max 건수 증가 시 프롬프트 길이 비례 증가 — LLM 컨텍스트 내 유지.
 *                  forecast.series가 null·빈 리스트면 "주가 반응 데이터 없음" 명시 — 억지 판단 방지.
 */
@Component
public class Stage4PromptBuilder {

    private static final int MAX_SIMILAR = 10;
    /** rationale 글자 수 상한 — PromptGuard와 정합(summary 240자 기준의 2배로 여유). */
    static final int RATIONALE_MAX_CHARS = 480;

    /**
     * Stage 4 판단 프롬프트 조립.
     *
     * @param ar       Stage 2까지 완료된 분석 결과
     * @param similar  Stage 3 유사 공시 목록(최대 10건)
     * @param forecast 유사 공시 D+1~D+5 실측 반응(표본 없으면 empty)
     */
    public String build(AnalysisResult ar, List<SimilarDisclosureItem> similar,
                        Optional<PriceReactionForecast> forecast) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                당신은 한국 DART 공시 분석 어시스턴트입니다.
                목적: 개인 투자자에게 참고용 방향성 정보를 제공(투자 자문 아님).
                금지: "매수/매도 추천", "꼭 사세요/파세요", "수익 보장", "확정 수익" 등 투자 권유 표현.
                expected_reaction(UP/FLAT/DOWN)은 과거 유사 사례 경향이지 미래 수익 보장이 아닙니다.

                ## 1차 분석 결과 (Stage 2)
                """);
        sb.append("- 감성: ").append(ar.getSentiment()).append("\n");
        sb.append("- 신뢰도: ").append(ar.getConfidence()).append("\n");
        sb.append("- 요약: ").append(ar.getSummary()).append("\n\n");

        sb.append("## 과거 유사 공시 사례 (Stage 3 RAG)\n");
        List<SimilarDisclosureItem> capped = similar.size() > MAX_SIMILAR
                ? similar.subList(0, MAX_SIMILAR) : similar;
        for (int i = 0; i < capped.size(); i++) {
            SimilarDisclosureItem s = capped.get(i);
            sb.append(String.format("%d. %s (%s, 유사도 %.0f%%)\n",
                    i + 1, s.corpName(), s.rceptDt(), s.similarityScore() * 100));
        }
        sb.append("\n");

        sb.append("## 유사 공시 이후 실측 주가 반응\n");
        if (forecast.isPresent() && !forecast.get().series().isEmpty()) {
            PriceReactionForecast f = forecast.get();
            sb.append(String.format("표본 %d건 평균:\n", f.sampleSize()));
            for (PriceReactionForecast.DayReaction dr : f.series()) {
                sb.append(String.format("  D+%d: %+.2f%%\n", dr.day(), dr.avgPct()));
            }
            sb.append(String.format("D+1~D+5 평균: %+.2f%%\n\n", f.avg5dPct()));
        } else {
            sb.append("주가 반응 데이터 없음(stock_prices 미적재 또는 표본 부족).\n\n");
        }

        sb.append("""
                ## 지시
                위 1차 분석과 유사 공시 사례를 종합하여 단기 주가 반응 방향을 판단하세요.
                제공된 수치만 사용하고, 제공되지 않은 사실을 추측하지 마세요.

                응답은 반드시 다음 JSON 스키마만으로 작성하세요. 다른 텍스트 추가 금지.
                {
                  "expected_reaction": "UP 또는 FLAT 또는 DOWN",
                  "rationale": "판단 근거를 2줄 이내 한국어로. 과거 사례 경향과 1차 분석의 어떤 점이 영향을 주었는지 서술. 투자 권유 표현 절대 금지.",
                  "confidence": 0.0 ~ 1.0 사이의 실수 (이 판단의 확신 정도)
                }
                """);
        return sb.toString();
    }

    /**
     * rationale 글자 수 캡 — PromptGuard와 정합.
     * Stage4Analyzer가 저장 전 호출.
     */
    public static String capRationale(String rationale) {
        if (rationale == null) return "";
        if (rationale.length() <= RATIONALE_MAX_CHARS) return rationale;
        String head = rationale.substring(0, RATIONALE_MAX_CHARS);
        int lastStop = Math.max(head.lastIndexOf('.'), Math.max(head.lastIndexOf('。'), head.lastIndexOf('!')));
        if (lastStop > RATIONALE_MAX_CHARS / 2) return head.substring(0, lastStop + 1);
        return head + "…";
    }
}
