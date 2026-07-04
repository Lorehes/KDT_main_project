package com.dartcommons.notification.services;

import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.disclosure.entities.Disclosure;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/*
 * [목적] 공시 알림 메시지(본문·제목)를 채널 무관하게 조립.
 *       면책 문구(자본시장법 §4 투자 권유 금지) 포함 — CLAUDE.md §7.
 * [이유] 채널마다 메시지를 재조립하면 문구 불일치 위험 → 단일 조립 지점 집중.
 *       confidence < 0.5 이면 "판단 보류" 명시(CLAUDE.md §6-6, api_spec §2.4).
 * [사이드 임팩트] MVP: disclosure.entity 직접 참조(cross-domain). Sentiment 공유 enum 이관 시 제거 대상.
 *               Kakao 알림톡은 승인된 템플릿 형식 준수 필요 — 실계정 연동 시 buildBody 재검토.
 * [수정 시 고려사항] HTML 이메일 전환 시 buildBody 오버로드(boolean html) 추가.
 *                  다국어 지원 필요 시 MessageSource 주입해 Locale 기반 출력.
 */
@Component
public class NotificationMessageBuilder {

    private static final String DISCLAIMER =
            "※ 본 내용은 AI 분석 요약으로, 투자 권유가 아닙니다.";

    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = BigDecimal.valueOf(0.5);

    /**
     * 알림 본문 조립. 신뢰도가 낮으면 "판단 보류" 문구 삽입.
     * confidence가 null이면 신뢰도 부기 생략.
     */
    public String buildBody(Disclosure disclosure, Sentiment sentiment, BigDecimal confidence) {
        String sentimentLabel = sentimentKorean(sentiment);
        String confNote = isLowConfidence(confidence) ? " (신뢰도 낮음, 판단 보류)" : "";

        return String.format(
                "[DART 공시 알림] %s\n" +
                "공시: %s\n" +
                "종목: %s(%s)\n" +
                "분류: %s%s\n\n" +
                "%s",
                disclosure.getCorpName(),
                disclosure.getReportNm(),
                disclosure.getCorpName(),
                disclosure.getStockCode() != null ? disclosure.getStockCode() : "-",
                sentimentLabel,
                confNote,
                DISCLAIMER
        );
    }

    /**
     * 이메일 제목 조립.
     */
    public String buildSubject(Disclosure disclosure, Sentiment sentiment) {
        return String.format("[DART 공시] [%s] %s", sentimentKorean(sentiment), disclosure.getCorpName());
    }

    private static String sentimentKorean(Sentiment sentiment) {
        return switch (sentiment) {
            case POSITIVE -> "호재";
            case NEGATIVE -> "악재";
            case NEUTRAL  -> "중립";
        };
    }

    private static boolean isLowConfidence(BigDecimal confidence) {
        return confidence != null && confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0;
    }
}
