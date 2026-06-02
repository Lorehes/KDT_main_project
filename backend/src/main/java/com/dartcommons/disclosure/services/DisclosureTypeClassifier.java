package com.dartcommons.disclosure.services;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/*
 * [목적] DART 공시 report_nm(보고서명) 키워드 매칭으로 disclosure_type을 룰 기반 분류하는 Stage 1 컴포넌트.
 * [이유] LLM 미사용 — Stage 1은 룰 기반으로 분류해 환각 리스크 없음(CLAUDE.md §6-6, spec §배경).
 *       pblntf_ty는 DART 응답에 미포함이라 report_nm 패턴만 사용.
 *       2026-06-03 보강: 백필 9.2만건 분석 결과 OTHER 61% → 16개 신규 유형 추가로 약 38k건 회수.
 * [사이드 임팩트] 매칭 실패 시 "OTHER" 반환 — 분류 정확도는 키워드 목록 보강으로 점진적 개선 가능.
 *               analysis 도메인의 Stage 2 LLM이 disclosureType을 입력으로 사용해 상세 분류.
 *               룰 변경 시 기존 disclosures.disclosure_type은 자동 갱신되지 않음 — 일괄 재분류 SQL 별도 실행.
 * [수정 시 고려사항] RULES 목록은 순서 의존(더 구체적인 키워드를 앞에 배치).
 *                  "주주총회" 같은 일반적 키워드는 뒤쪽에 두어 더 구체적 매칭이 먼저 일어나도록.
 *                  [기재정정] 같은 prefix는 contains 방식이라 자연스럽게 흡수됨.
 *                  정규식이 필요한 패턴은 String.matches() 대신 별도 TypeRule variant 추가.
 */
@Component
public class DisclosureTypeClassifier {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final List<TypeRule> RULES = List.of(
            // ─── 정기공시 ───
            new TypeRule("ANNUAL_REPORT",        "사업보고서"),
            new TypeRule("SEMI_ANNUAL_REPORT",   "반기보고서"),
            new TypeRule("QUARTERLY_REPORT",     "분기보고서"),

            // ─── 실적/예고 ───
            new TypeRule("EARNINGS_PRELIMINARY", "잠정실적", "잠정)실적", "영업(잠정)"),
            new TypeRule("EARNINGS_FORECAST",    "결산실적공시예고"),
            new TypeRule("MATERIAL_EARNINGS_CHANGE", "매출액또는손익구조"),

            // ─── 증자·감자 ───
            new TypeRule("RIGHTS_OFFERING",      "유상증자"),
            new TypeRule("BONUS_ISSUE",          "무상증자"),
            new TypeRule("CAPITAL_REDUCTION",    "감자"),

            // ─── 증권/채권 발행 (구체→일반) ───
            new TypeRule("CONVERTIBLE_BOND",     "전환사채"),
            new TypeRule("BOND_WITH_WARRANT",    "신주인수권부사채"),
            new TypeRule("EXCHANGEABLE_BOND",    "교환사채"),
            new TypeRule("DERIVATIVE_ISSUANCE",  "파생결합사채", "파생결합증권"),
            new TypeRule("SECURITIES_ISSUANCE",  "증권발행실적보고서"),
            new TypeRule("PROSPECTUS",           "투자설명서"),
            new TypeRule("BOND_ISSUANCE",        "증권신고서(채무증권)", "채무증권"),

            // ─── M&A·구조 ───
            new TypeRule("MERGER",               "합병"),
            new TypeRule("ACQUISITION",          "양수도"),
            new TypeRule("SPIN_OFF",             "분할"),
            new TypeRule("DELISTING",            "상장폐지"),
            new TypeRule("LISTING",              "상장"),

            // ─── 주주·지분 (구체→일반) ───
            new TypeRule("MAJOR_SHAREHOLDER_CHANGE", "최대주주변경", "최대주주등소유주식변동"),
            new TypeRule("LARGE_STAKE_CHANGE",   "주식등의대량보유"),
            new TypeRule("EXECUTIVE_SHARE",      "임원ㆍ주요주주특정증권"),

            // ─── 주주총회 (의결권/소집/명부 모두) ───
            new TypeRule("PROXY_SOLICITATION",   "의결권대리행사권유"),
            new TypeRule("SHAREHOLDER_MEETING",  "주주총회", "주주명부폐쇄"),

            // ─── IR/감사/거버넌스 ───
            new TypeRule("IR_EVENT",             "기업설명회"),
            new TypeRule("AUDIT_REPORT",         "감사보고서"),
            new TypeRule("GOVERNANCE_REPORT",    "기업지배구조보고서"),
            new TypeRule("ESG_REPORT",           "지속가능경영"),
            new TypeRule("CONGLOMERATE_DISCLOSURE", "대규모기업집단현황"),

            // ─── 임원·옵션 ───
            new TypeRule("STOCK_OPTION",         "주식매수선택권"),

            // ─── 계약·거래 ───
            new TypeRule("SUPPLY_CONTRACT",      "단일판매", "공급계약"),
            new TypeRule("RELATED_PARTY_TRANSACTION", "특수관계인", "동일인등출자계열회사"),
            new TypeRule("GUARANTEE_DECISION",   "채무보증"),

            // ─── 배당·자기주식 ───
            new TypeRule("DIVIDEND",             "배당"),
            new TypeRule("TREASURY_STOCK",       "자기주식"),

            // ─── 경영 위기 ───
            new TypeRule("AUDIT_OPINION",        "감사의견"),
            new TypeRule("GOING_CONCERN",        "계속기업"),
            new TypeRule("BANKRUPTCY",           "부도"),
            new TypeRule("REHABILITATION",       "회생"),
            new TypeRule("LIQUIDATION",          "해산", "청산"),

            // ─── 소송·제재 ───
            new TypeRule("LITIGATION",           "소송"),
            new TypeRule("SANCTION",             "제재"),

            // ─── 임원 변경 (사외이사 포함) ───
            new TypeRule("EXECUTIVE_CHANGE",     "임원변경", "대표이사변경", "사외이사의선임", "사외이사의해임"),

            // ─── 정보 공시 (풍문/투자판단/분쟁조정) ───
            new TypeRule("RUMOR_CLARIFICATION",  "풍문또는보도", "보도에대한해명"),
            new TypeRule("MATERIAL_BUSINESS_MATTER", "투자판단관련주요경영사항"),
            new TypeRule("DISPUTE_RESOLUTION",   "지급수단별", "분쟁조정기구")
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
