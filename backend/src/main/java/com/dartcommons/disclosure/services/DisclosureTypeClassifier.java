package com.dartcommons.disclosure.services;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/*
 * [목적] DART 공시 report_nm(보고서명) 키워드 매칭으로 disclosure_type을 룰 기반 분류하는 Stage 1 컴포넌트.
 * [이유] LLM 미사용 — Stage 1은 룰 기반으로 분류해 환각 리스크 없음(CLAUDE.md §6-6, spec §배경).
 *       pblntf_ty는 DART 응답에 미포함이라 report_nm 패턴만 사용.
 * [사이드 임팩트] 매칭 실패 시 "OTHER" 반환 — 분류 정확도는 키워드 목록 보강으로 점진적 개선 가능.
 *               analysis 도메인의 Stage 2 LLM이 disclosureType을 입력으로 사용해 상세 분류.
 * [수정 시 고려사항] RULES 목록은 순서 의존(더 구체적인 키워드를 앞에 배치).
 *                  "단일판매·공급계약"은 SUPPLY_CONTRACT 로 통합 — 향후 세분화 필요 시 별도 타입 추가.
 *                  정규식이 필요한 패턴은 String.matches() 대신 별도 TypeRule variant 추가.
 */
@Component
public class DisclosureTypeClassifier {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<TypeRule> RULES = List.of(
            // 정기공시
            new TypeRule("ANNUAL_REPORT",        "사업보고서"),
            new TypeRule("SEMI_ANNUAL_REPORT",   "반기보고서"),
            new TypeRule("QUARTERLY_REPORT",     "분기보고서"),

            // 증자·감자
            new TypeRule("RIGHTS_OFFERING",      "유상증자"),
            new TypeRule("BONUS_ISSUE",          "무상증자"),
            new TypeRule("CAPITAL_REDUCTION",    "감자"),

            // 채권 발행
            new TypeRule("CONVERTIBLE_BOND",     "전환사채"),
            new TypeRule("BOND_WITH_WARRANT",    "신주인수권부사채"),
            new TypeRule("EXCHANGEABLE_BOND",    "교환사채"),

            // M&A·구조
            new TypeRule("MERGER",               "합병"),
            new TypeRule("ACQUISITION",          "양수도"),
            new TypeRule("SPIN_OFF",             "분할"),
            new TypeRule("DELISTING",            "상장폐지"),
            new TypeRule("LISTING",              "상장"),

            // 주주·지분
            new TypeRule("MAJOR_SHAREHOLDER_CHANGE", "최대주주변경"),
            new TypeRule("LARGE_STAKE_CHANGE",   "주식등의대량보유"),
            new TypeRule("EXECUTIVE_SHARE",      "임원ㆍ주요주주특정증권"),

            // 계약
            new TypeRule("SUPPLY_CONTRACT",      "단일판매", "공급계약"),

            // 배당·자기주식
            new TypeRule("DIVIDEND",             "배당"),
            new TypeRule("TREASURY_STOCK",       "자기주식"),

            // 경영 위기
            new TypeRule("AUDIT_OPINION",        "감사의견"),
            new TypeRule("GOING_CONCERN",        "계속기업"),
            new TypeRule("BANKRUPTCY",           "부도"),
            new TypeRule("REHABILITATION",       "회생"),
            new TypeRule("LIQUIDATION",          "해산", "청산"),

            // 소송·제재
            new TypeRule("LITIGATION",           "소송"),
            new TypeRule("SANCTION",             "제재"),

            // 임원 변경
            new TypeRule("EXECUTIVE_CHANGE",     "임원변경", "대표이사변경")
    );

    /**
     * 공시 보고서명(report_nm)에서 공시 유형 문자열을 추출한다.
     * 매칭 실패 시 "OTHER" 반환 — analysis 도메인에서 추가 분류.
     */
    public String classify(String reportNm) {
        if (reportNm == null || reportNm.isBlank()) return "OTHER";

        String normalized = WHITESPACE.matcher(reportNm).replaceAll("");

        for (TypeRule rule : RULES) {
            if (rule.matches(normalized)) return rule.type();
        }
        return "OTHER";
    }

    private record TypeRule(String type, String... keywords) {
        boolean matches(String normalized) {
            for (String keyword : keywords) {
                if (normalized.contains(keyword)) return true;
            }
            return false;
        }
    }
}
