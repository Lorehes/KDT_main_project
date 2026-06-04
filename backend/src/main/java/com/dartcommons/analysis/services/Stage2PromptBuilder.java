package com.dartcommons.analysis.services;

import com.dartcommons.disclosure.entities.Disclosure;
import org.springframework.stereotype.Component;

/*
 * [목적] Stage 2 LLM 호출 프롬프트 생성 — DART 공시 메타(report_nm + disclosure_type + corp_name + rcept_dt)를
 *       투자 자문 표현 금지 가드 포함 한국어 프롬프트로 직렬화.
 * [이유] analysis-stage2-llm Spec §6.2: 본문 미사용 + 메타 기반 분류 + 환각 방지 record 스키마.
 *       통합기획서 §11.1 리스크 1: 프롬프트에 자본시장법 가드 명시(L1) — 응답 후처리 PromptGuard(L2)와 이중.
 * [사이드 임팩트] 프롬프트 변경은 LLM 응답 품질에 직접 영향 — A/B 통계로만 변경 권장.
 *               disclosure_type=OTHER (8%) 케이스는 report_nm만 신뢰하도록 안내 → 신뢰도 자연 하락 → withheld 가드.
 * [수정 시 고려사항] 모델별(qwen/gemma) 프롬프트 민감도 다름 — wave 2 smoke test 결과로 보강.
 *                  영어 모델 사용 시 한국어 프롬프트 효과 저하 가능 — 향후 다국어 분기.
 *                  본문(content_text) 추출 후속 Spec에서는 본 빌더에 본문 섹션 추가.
 */
@Component
public class Stage2PromptBuilder {

    /*
     * 시스템 + 사용자 통합 프롬프트.
     * - "정보 제공 목적"·"자문 아님" 가드를 명시
     * - 출력 스키마 강제 (LLM이 JSON 외 텍스트 추가하지 않도록)
     * - disclosure_type=OTHER 분기 안내
     */
    public String build(Disclosure d) {
        String typeHint = "OTHER".equals(d.getDisclosureType())
                ? "분류 미상이므로 보고서 제목의 키워드만 신뢰하세요."
                : "분류값을 참고하여 의미를 해석하세요.";

        return """
                당신은 한국 DART 공시 분석 어시스턴트입니다.
                목적: 개인 투자자에게 공시 의미를 자연어로 정보 제공.
                금지: "매수/매도 추천", "꼭 사세요/파세요", "수익 보장", "확정 수익" 등 투자 권유 표현.
                본 분석은 투자 자문이 아니며 참고용 정보입니다.

                다음 공시를 분석해 호재/중립/악재 중 하나로 분류하세요.

                공시 정보:
                - 회사명: %s
                - 보고서 제목: %s
                - 분류값: %s (%s)
                - 접수일: %s

                응답은 반드시 다음 JSON 스키마만으로 작성하세요. 다른 텍스트 추가 금지.
                {
                  "sentiment": "POSITIVE 또는 NEUTRAL 또는 NEGATIVE",
                  "confidence": 0.0 ~ 1.0 사이의 실수 (확신 정도),
                  "summary": "3줄 이내 한국어 자연어 요약"
                }

                중요:
                - 회사명·보고서 제목·수치·날짜는 원본 그대로 인용. 변형 금지.
                - 확신이 약하면 confidence를 낮게(예: 0.4) 보고하세요. 추측 금지.
                - summary는 마침표로 끝나는 완결 문장 3줄 이내.
                """.formatted(
                d.getCorpName(),
                d.getReportNm(),
                d.getDisclosureType(),
                typeHint,
                d.getRceptDt()
        );
    }
}
