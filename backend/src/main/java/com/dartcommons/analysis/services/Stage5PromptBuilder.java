package com.dartcommons.analysis.services;

import com.dartcommons.analysis.entities.AnalysisResult;
import com.dartcommons.stocks.entities.FinancialSnapshot;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/*
 * [목적] Stage 5 LLM 재무/업황 분석 프롬프트 생성.
 *       Stage 2/4 결과 + 최근 2분기 재무 스냅샷(서버 계산 수치 그대로 주입)을 단일 프롬프트로 조립.
 * [이유] 환각 방지(CLAUDE.md §4): 수치는 서버가 룰 기반 산출(당기·전기·증감률) → 프롬프트에 주입만.
 *       LLM 출력 스키마는 방향성 서술 중심 — 수치 재인용 최소화로 환각 차단.
 *       자본시장법 가드 L1 명시(통합기획서 §11.1) — "재무 악화"(사실) 허용 / "매도 권고" 금지.
 * [사이드 임팩트] 재무 스냅샷 없으면 Stage5Analyzer가 이 빌더를 호출하지 않음(게이트 ④ skip).
 *               업황(industryContext)은 현재 null 고정 — 업황 후속 Spec 구현 시 이 빌더에 섹션 추가.
 * [수정 시 고려사항] 스냅샷 비교 깊이(현재 2건): 더 긴 추세가 필요하면 snapshotsDesc 크기 확장.
 *                  금융업 계정체계 상이 시 revenue/opProfit null 가능 → 프롬프트에 "미보고" 명시.
 */
@Component
public class Stage5PromptBuilder {

    /** rationale 길이 상한 — Stage4PromptBuilder와 동일 정책. */
    static final int FINANCIAL_IMPACT_MAX_CHARS = 480;

    /**
     * Stage 5 프롬프트 조립.
     *
     * @param ar              Stage 4까지 완료된 분석 결과
     * @param snapshotsDesc   재무 스냅샷 목록 (최신순, 최대 2건 사용 — 당기·전기 증감 계산)
     */
    public String build(AnalysisResult ar, List<FinancialSnapshot> snapshotsDesc) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                당신은 한국 DART 공시 분석 어시스턴트입니다.
                목적: 재무 데이터를 근거로 공시의 재무 영향과 리스크를 참고용 정보로 서술.
                금지: "매수/매도 추천", "꼭 사세요/파세요", "수익 보장", "확정 수익" 등 투자 권유 표현.
                본 분석은 투자 자문이 아닌 참고 정보입니다.

                ## 1차~4차 분석 결과 요약
                """);
        sb.append("- 감성: ").append(ar.getSentiment()).append("\n");
        sb.append("- 요약: ").append(ar.getSummary()).append("\n");
        if (ar.getRationale() != null) {
            sb.append("- 최종 판단 근거: ").append(ar.getRationale()).append("\n");
        }
        sb.append("\n## 최근 재무 데이터 (DART 제출 기준 — 서버 계산값, 원 단위)\n");

        if (snapshotsDesc.isEmpty()) {
            sb.append("재무 데이터 없음.\n");
        } else {
            FinancialSnapshot latest = snapshotsDesc.get(0);
            sb.append(String.format("기준: %s년 %s (fs_div: %s)\n",
                    latest.getBsnsYear(), reprtLabel(latest.getReprtCode()), latest.getFsDiv()));
            sb.append(formatAmount("매출액", latest.getRevenue()));
            sb.append(formatAmount("영업이익", latest.getOpProfit()));
            sb.append(formatAmount("당기순이익", latest.getNetIncome()));
            sb.append(formatAmount("자산총계", latest.getTotalAssets()));
            sb.append(formatAmount("부채총계", latest.getTotalLiab()));
            sb.append(formatAmount("자본총계", latest.getTotalEquity()));

            if (snapshotsDesc.size() >= 2) {
                FinancialSnapshot prev = snapshotsDesc.get(1);
                sb.append(String.format("\n전기 대비 증감 (%s년 %s 기준):\n",
                        prev.getBsnsYear(), reprtLabel(prev.getReprtCode())));
                sb.append(formatChange("매출액 증감률", latest.getRevenue(), prev.getRevenue()));
                sb.append(formatChange("영업이익 증감률", latest.getOpProfit(), prev.getOpProfit()));
                sb.append(formatChange("당기순이익 증감률", latest.getNetIncome(), prev.getNetIncome()));
                sb.append(formatChange("부채비율 변화", computeDebtRatio(latest), computeDebtRatio(prev)));
            }
        }

        sb.append("""

                ## 지시
                위 1차~4차 분석과 재무 데이터를 종합해 다음 JSON 스키마만으로 응답하세요. 다른 텍스트 추가 금지.
                재무 수치는 위에 제공된 값만 사용하고, 없는 수치를 추측하지 마세요.
                financial_impact·risk_assessment는 사실 기반 서술. 방향성 정보이며 투자 권유 표현 절대 금지.

                {
                  "financial_impact": "공시와 재무 데이터를 연결한 재무 영향 2문장 이내 한국어.",
                  "risk_assessment": "재무 리스크 요인 2문장 이내. 사실 서술만, 권유 없음.",
                  "industry_context": null
                }
                """);
        return sb.toString();
    }

    private static String formatAmount(String label, BigDecimal val) {
        if (val == null) return String.format("  %s: 미보고\n", label);
        return String.format("  %s: %,d 원\n", label, val.longValue());
    }

    private static String formatChange(String label, BigDecimal curr, BigDecimal prev) {
        if (curr == null || prev == null || prev.compareTo(BigDecimal.ZERO) == 0) {
            return String.format("  %s: 산출 불가\n", label);
        }
        BigDecimal rate = curr.subtract(prev)
                .divide(prev.abs(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        return String.format("  %s: %+.1f%%\n", label, rate);
    }

    private static BigDecimal computeDebtRatio(FinancialSnapshot s) {
        if (s.getTotalLiab() == null || s.getTotalEquity() == null
                || s.getTotalEquity().compareTo(BigDecimal.ZERO) == 0) return null;
        return s.getTotalLiab().divide(s.getTotalEquity(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private static String reprtLabel(String code) {
        return switch (code) {
            case "11011" -> "사업보고서";
            case "11012" -> "반기보고서";
            case "11013" -> "1분기보고서";
            case "11014" -> "3분기보고서";
            default -> code;
        };
    }

    public static String capText(String text) {
        if (text == null) return "";
        if (text.length() <= FINANCIAL_IMPACT_MAX_CHARS) return text;
        String head = text.substring(0, FINANCIAL_IMPACT_MAX_CHARS);
        int lastStop = Math.max(head.lastIndexOf('.'),
                Math.max(head.lastIndexOf('。'), Math.max(head.lastIndexOf('!'), head.lastIndexOf('?'))));
        if (lastStop > FINANCIAL_IMPACT_MAX_CHARS / 2) return head.substring(0, lastStop + 1);
        return head + "…";
    }
}
