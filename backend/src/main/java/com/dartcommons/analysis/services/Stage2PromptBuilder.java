package com.dartcommons.analysis.services;

import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.infrastructure.llm.LlmProperties;
import org.springframework.stereotype.Component;

/*
 * [목적] Stage 2 LLM 호출 프롬프트 생성 — DART 공시 메타(report_nm·disclosure_type·corp_name·rcept_dt) +
 *       본문 발췌(content_text 앞 stage2BodyMaxChars자)를 투자 자문 금지 가드 포함 한국어 프롬프트로 직렬화.
 * [이유] Wave 2(disclosure-detail-redesign)가 key_points·호재/악재 요인 필드를 추가했으나, 메타(제목)만으론
 *       "제목 에코" 수준이라 목업의 실질 해설이 안 나옴(2026-07-03 실측). stage2-body-in-prompt Spec:
 *       본문 발췌를 프롬프트에 넣어 실제 공시 내용 기반 해설·요인 생성. 본문 없으면 메타 전용 폴백.
 *       통합기획서 §11.1: 프롬프트 자본시장법 가드(L1) 명시 — 응답 후처리 PromptGuard(L2)와 이중.
 * [사이드 임팩트] 본문 투입으로 입력 토큰 급증 → OllamaLlmClient num_ctx 상향 필수(안 하면 본문 절단됨).
 *               OpenRouter는 입력 토큰당 과금 → 공시당 비용↑. stage2BodyMaxChars(설정, 기본 6000)로 균형.
 *               본문 참조 사실↑ → 해설 품질↑이나 수치·상대방 환각 표면도↑ → 원본 인용 변형 금지 지시 강화 + PromptGuard 유지.
 * [수정 시 고려사항] stage2BodyMaxChars=0이면 본문 미투입(메타 전용). 본문 앞 N자 커버율은 DART 공시가 핵심을
 *                  앞에 배치하는 특성에 의존 — 뒤쪽 조건 누락 가능(요약/섹션 추출은 후속).
 *                  모델별(qwen/gemma) 본문 처리 품질 상이 — smoke test로 확정([[analysis-stage2-smoke]]).
 */
@Component
public class Stage2PromptBuilder {

    private final LlmProperties props;

    public Stage2PromptBuilder(LlmProperties props) {
        this.props = props;
    }

    /*
     * 시스템 + 사용자 통합 프롬프트.
     * - "정보 제공 목적"·"자문 아님" 가드 명시 / 출력 JSON 스키마 강제
     * - content_text 있고 stage2BodyMaxChars>0이면 본문 발췌 섹션 포함 → 본문 근거 해설 지시
     * - 본문 없으면 메타 전용(제목 기반) — 요인 빈약 감수
     */
    public String build(Disclosure d) {
        String typeHint = "OTHER".equals(d.getDisclosureType())
                ? "분류 미상이므로 보고서 제목의 키워드만 신뢰하세요."
                : "분류값을 참고하여 의미를 해석하세요.";

        String bodyExcerpt = truncate(d.getContentText(), props.stage2BodyMaxChars());
        boolean hasBody = bodyExcerpt != null && !bodyExcerpt.isBlank();

        // 본문 유무에 따라 프롬프트에 삽입할 본문 섹션 + 근거 지시를 분기.
        String bodySection = hasBody
                ? "\n공시 본문(발췌, 앞부분):\n\"\"\"\n" + bodyExcerpt + "\n\"\"\"\n"
                : "";
        String bodyInstruction = hasBody
                ? "- key_points·요인은 위 본문 발췌의 사실에 근거해 작성하세요. 발췌에 없는 사실은 추측하지 마세요.\n"
                + "                - 본문의 수치·계약상대방·날짜는 발췌에 나온 그대로만 인용하고, 없으면 언급하지 마세요."
                : "- 본문이 제공되지 않았습니다. 제목·분류값만으로 판단하고, 불확실하면 confidence를 낮추세요.";

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
                %s
                응답은 반드시 다음 JSON 스키마만으로 작성하세요. 다른 텍스트 추가 금지.
                {
                  "sentiment": "POSITIVE 또는 NEUTRAL 또는 NEGATIVE",
                  "confidence": 0.0 ~ 1.0 사이의 실수 (확신 정도),
                  "summary": "3줄 이내 한국어 자연어 요약",
                  "key_points": ["공시의 핵심 내용을 사실 기반으로 1~4개 항목. 각 항목은 한 문장."],
                  "positive_factors": ["투자자 관점의 호재 요인 0~3개. 없으면 빈 배열 []."],
                  "negative_factors": ["투자자 관점의 악재 요인 0~3개. 없으면 빈 배열 []."]
                }

                중요:
                - 회사명·보고서 제목·수치·날짜는 원본 그대로 인용. 변형 금지.
                - 확신이 약하면 confidence를 낮게(예: 0.4) 보고하세요. 추측 금지.
                - summary는 마침표로 끝나는 완결 문장 3줄 이내.
                - key_points는 공시 내용의 사실 해설(무엇이 결정/발생했는가). 추측·전망 금지.
                - positive_factors/negative_factors는 정보 제공용 중립 서술. "매수/매도", "사세요", "수익 보장" 등 권유 표현 절대 금지.
                - 해당 요인이 없으면 빈 배열 []로 두세요. 억지로 채우지 마세요.
                %s
                """.formatted(
                d.getCorpName(),
                d.getReportNm(),
                d.getDisclosureType(),
                typeHint,
                d.getRceptDt(),
                bodySection,
                bodyInstruction
        );
    }

    /**
     * content_text를 limit 글자로 절삭 — 서로게이트 페어 경계 보호(BMP 외 문자 반쪽 절단 방지).
     * DisclosureContentService.truncate와 동일 원리. limit<=0 또는 null이면 본문 미투입(null 반환).
     */
    private static String truncate(String text, int limit) {
        if (text == null || limit <= 0) return null;
        if (text.length() <= limit) return text;
        int cut = limit;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) cut--;  // 반쪽 서로게이트 제거
        return text.substring(0, cut);
    }
}
