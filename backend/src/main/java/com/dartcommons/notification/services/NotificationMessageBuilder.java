package com.dartcommons.notification.services;

import com.dartcommons.shared.enums.Sentiment;
import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.infrastructure.telegram.TelegramProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/*
 * [목적] 공시 알림 메시지(본문·제목)를 조립 — 채널 무관 텍스트(buildBody) + 텔레그램 HTML(buildTelegramBody).
 *       면책 문구(자본시장법 §11.1 투자 권유 금지) 포함 — CLAUDE.md §7.
 * [이유] 채널마다 메시지를 재조립하면 문구 불일치 위험 → 단일 조립 지점 집중.
 *       confidence < 0.5 이면 "판단 보류" 명시(CLAUDE.md §6-6, api_spec §2.4).
 *       본문은 발송 시점에 notifications.message_body로 저장되고 RetryJob이 재사용하므로
 *       채널별 포맷은 반드시 이 조립 시점에 확정되어야 한다(telegram-notification-channel Tech Review §1).
 * [사이드 임팩트] MVP: disclosure.entity 직접 참조(cross-domain). Sentiment 공유 enum 이관 시 제거 대상.
 *               Kakao 알림톡은 승인된 템플릿 형식 준수 필요 — 실계정 연동 시 buildBody 재검토.
 *               텔레그램 본문은 parse_mode=HTML 전제 — 사용자 데이터(회사명/제목/요약)는 HTML 이스케이프 필수.
 * [수정 시 고려사항] 면책 문구 2종 분리 이유: DISCLAIMER는 카카오 알림톡 심사 템플릿 문구와 결합(변경 시 재심사),
 *                  TELEGRAM_DISCLAIMER는 §11.2 전문 — 통일하려면 카카오 템플릿 재심사 후 DISCLAIMER로 일원화.
 *                  배지 색 관례: 한국 증시 = 호재/상승 빨강(🔴)·악재/하락 파랑(🔵) — 서구권과 반대(Spec R6 확정안).
 *                  다국어 지원 필요 시 MessageSource 주입해 Locale 기반 출력.
 */
@Component
public class NotificationMessageBuilder {

    private static final String DISCLAIMER =
            "※ 본 내용은 AI 분석 요약으로, 투자 권유가 아닙니다.";

    /** §11.2 면책 전문 — 텔레그램은 템플릿 심사 제약이 없어 전문 사용(Spec R6 확정안). */
    private static final String TELEGRAM_DISCLAIMER =
            "※ 본 분석은 정보 제공 목적이며 투자 자문·권유가 아닙니다.\n"
            + "AI 분석은 부정확할 수 있으며 투자 판단의 책임은 이용자에게 있습니다.";

    private static final BigDecimal LOW_CONFIDENCE_THRESHOLD = BigDecimal.valueOf(0.5);

    /** 텔레그램 메시지 한도 4,096자 — 요약이 본문을 밀어내지 않도록 캡(말줄임). */
    private static final int TELEGRAM_SUMMARY_MAX_CHARS = 800;

    private final TelegramProperties telegramProperties;

    public NotificationMessageBuilder(TelegramProperties telegramProperties) {
        this.telegramProperties = telegramProperties;
    }

    /**
     * 알림 본문 조립(카카오/이메일 — 평문). 신뢰도가 낮으면 "판단 보류" 문구 삽입.
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
     * 텔레그램 HTML 본문 조립(parse_mode=HTML 전제).
     * 배지는 색상(이모지)+텍스트 병용(색맹 배려, CLAUDE.md §6-5) — 한국 증시 관례(호재=🔴/악재=🔵).
     * 신뢰도 낮음(<0.5)이면 배지·신뢰도 대신 "판단 보류" 표기(신뢰도 없이 단정 금지, CLAUDE.md §7).
     * summary는 nullable — 없으면 해당 단락 생략(구버전 분석·Stage 2 미완료 호환).
     */
    public String buildTelegramBody(Disclosure disclosure, Sentiment sentiment,
                                    BigDecimal confidence, String summary) {
        // 본문에 삽입되는 모든 외부 유래 문자열은 escapeHtml 필수(불변조건) — stockCode·frontBaseUrl 포함
        String corpName = escapeHtml(disclosure.getCorpName());
        String stockCode = escapeHtml(disclosure.getStockCode() != null ? disclosure.getStockCode() : "-");
        String reportNm = escapeHtml(disclosure.getReportNm());

        StringBuilder sb = new StringBuilder();
        sb.append("📢 <b>[DART 공시 알림]</b> ").append(corpName)
          .append(" (").append(stockCode).append(")\n\n");

        if (isLowConfidence(confidence)) {
            sb.append("⏸ 판단 보류 — 신뢰도가 낮아 호재/악재 판단을 보류합니다\n");
            sb.append("<b>").append(reportNm).append("</b>\n\n");
        } else {
            sb.append(sentimentBadge(sentiment)).append(" · <b>").append(reportNm).append("</b>\n\n");
        }

        if (summary != null && !summary.isBlank()) {
            sb.append(escapeHtml(truncate(summary.strip(), TELEGRAM_SUMMARY_MAX_CHARS))).append("\n\n");
        }

        if (!isLowConfidence(confidence) && confidence != null) {
            sb.append("신뢰도 ").append(toPercent(confidence)).append("%\n\n");
        }

        sb.append("🔗 상세 분석 보기: ")
          .append(escapeHtml(telegramProperties.frontBaseUrl())).append("/disclosures/").append(disclosure.getId())
          .append("\n\n")
          .append(TELEGRAM_DISCLAIMER);

        return sb.toString();
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

    /** 한국 증시 색 관례: 호재/상승=빨강, 악재/하락=파랑 (Spec R6 — 서구권과 반대). */
    private static String sentimentBadge(Sentiment sentiment) {
        return switch (sentiment) {
            case POSITIVE -> "🔴 호재";
            case NEGATIVE -> "🔵 악재";
            case NEUTRAL  -> "⚪ 중립";
        };
    }

    private static boolean isLowConfidence(BigDecimal confidence) {
        return confidence != null && confidence.compareTo(LOW_CONFIDENCE_THRESHOLD) < 0;
    }

    private static int toPercent(BigDecimal confidence) {
        return confidence.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).intValue();
    }

    /** parse_mode=HTML에서 의미를 갖는 3문자만 이스케이프(텔레그램 Bot API 규격). */
    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String truncate(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "…";
    }
}
