package com.dartcommons.notification;

import com.dartcommons.disclosure.entities.Disclosure;
import com.dartcommons.infrastructure.telegram.TelegramProperties;
import com.dartcommons.notification.services.NotificationMessageBuilder;
import com.dartcommons.shared.enums.Sentiment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * [목적] NotificationMessageBuilder 단위 테스트 — 텔레그램 HTML 본문(배지·판단보류·이스케이프·면책·링크·캡).
 * [이유] 메시지 본문은 발송 시점에 저장되어 RetryJob이 재사용 — 조립 로직 오류는 잘못된 문구가 영구 기록됨.
 *       자본시장법 면책 문구·판단 보류 표기는 컴플라이언스 필수(CLAUDE.md §6-6·§7) — 회귀 방지.
 * [사이드 임팩트] 없음(순수 단위 테스트 — Spring 컨텍스트 미기동).
 * [수정 시 고려사항] 배지 색 관례(호재=🔴/악재=🔵, 한국 증시)가 바뀌면 Spec R6과 함께 갱신.
 */
class NotificationMessageBuilderTest {

    private final TelegramProperties props = new TelegramProperties(
            "https://api.telegram.org", "placeholder", "test_bot", "http://localhost:3000", 1000, 3);

    private final NotificationMessageBuilder builder = new NotificationMessageBuilder(props);

    private Disclosure disclosure(String corpName, String reportNm) {
        Disclosure d = Disclosure.builder()
                .rceptNo("20260706000001")
                .corpCode("00126380")
                .stockCode("005930")
                .corpName(corpName)
                .reportNm(reportNm)
                .rceptDt(LocalDate.of(2026, 7, 6))
                .disclosureType("CONTRACT")
                .build();
        ReflectionTestUtils.setField(d, "id", 41L);
        return d;
    }

    @Test
    @DisplayName("호재 → 🔴 호재 배지(한국 증시 색 관례) + 신뢰도 % + 상세 링크 + 면책 문구")
    void telegramBody_positive_hasBadgeConfidenceLinkDisclaimer() {
        String body = builder.buildTelegramBody(
                disclosure("삼성전자", "단일판매ㆍ공급계약체결"), Sentiment.POSITIVE,
                BigDecimal.valueOf(0.85), "3줄 요약입니다.");

        assertThat(body).contains("🔴 호재");
        assertThat(body).contains("신뢰도 85%");
        assertThat(body).contains("http://localhost:3000/disclosures/41");
        assertThat(body).contains("투자 자문·권유가 아닙니다");
        assertThat(body).contains("3줄 요약입니다.");
    }

    @Test
    @DisplayName("악재 → 🔵 악재 배지 / 중립 → ⚪ 중립")
    void telegramBody_sentimentBadges() {
        String negative = builder.buildTelegramBody(disclosure("A", "B"), Sentiment.NEGATIVE,
                BigDecimal.valueOf(0.8), null);
        String neutral = builder.buildTelegramBody(disclosure("A", "B"), Sentiment.NEUTRAL,
                BigDecimal.valueOf(0.8), null);

        assertThat(negative).contains("🔵 악재");
        assertThat(neutral).contains("⚪ 중립");
    }

    @Test
    @DisplayName("신뢰도 < 0.5 → 배지·신뢰도 대신 '판단 보류' 표기(신뢰도 없이 단정 금지)")
    void telegramBody_lowConfidence_withheldNote() {
        String body = builder.buildTelegramBody(disclosure("삼성전자", "공시"), Sentiment.POSITIVE,
                BigDecimal.valueOf(0.30), null);

        assertThat(body).contains("판단 보류");
        assertThat(body).doesNotContain("🔴 호재");
        assertThat(body).doesNotContain("신뢰도 30%");
    }

    @Test
    @DisplayName("회사명·제목의 HTML 특수문자 이스케이프 (parse_mode=HTML 파싱 오류 방지)")
    void telegramBody_escapesHtml() {
        String body = builder.buildTelegramBody(
                disclosure("A&B<주식회사>", "지분 <5%> 변동 & 보고"), Sentiment.NEUTRAL,
                BigDecimal.valueOf(0.7), "요약 <b>굵게</b> & 끝");

        assertThat(body).contains("A&amp;B&lt;주식회사&gt;");
        assertThat(body).contains("지분 &lt;5%&gt; 변동 &amp; 보고");
        assertThat(body).contains("요약 &lt;b&gt;굵게&lt;/b&gt; &amp; 끝");
    }

    @Test
    @DisplayName("요약 800자 초과 → 말줄임(…) 캡 (텔레그램 4096자 한도 보호)")
    void telegramBody_longSummary_truncated() {
        String longSummary = "가".repeat(1200);
        String body = builder.buildTelegramBody(disclosure("A", "B"), Sentiment.POSITIVE,
                BigDecimal.valueOf(0.9), longSummary);

        assertThat(body).contains("가".repeat(800) + "…");
        assertThat(body).doesNotContain("가".repeat(801));
        assertThat(body.length()).isLessThan(4096);
    }

    @Test
    @DisplayName("summary null/blank → 요약 단락 생략(구버전 분석 호환)")
    void telegramBody_noSummary_skipsParagraph() {
        String body = builder.buildTelegramBody(disclosure("삼성전자", "공시"), Sentiment.POSITIVE,
                BigDecimal.valueOf(0.9), "  ");

        assertThat(body).contains("🔴 호재");
        assertThat(body).contains("신뢰도 90%");
    }
}
