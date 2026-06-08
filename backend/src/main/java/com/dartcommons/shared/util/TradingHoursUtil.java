package com.dartcommons.shared.util;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/*
 * [목적] KRX 정규 거래 시간(월~금 09:00~15:30 KST) 내 여부를 판단하는 유틸리티.
 *       feature_structure §1.1: 장운영 판단은 shared/에 위임.
 * [이유] 알림 발송 시 off_hours_allowed=false 사용자는 거래시간 외 발송을 스킵해야 함.
 *       ZonedDateTime 기반으로 테스트 가능(시각 주입)하게 설계 — Clock 주입 불필요.
 * [사이드 임팩트] 공휴일은 MVP 범위 외(평일 기준만 판단). 공휴일에 알림 발송 가능 — 추후 공공 API 연동 개선.
 *               KRX 일부 섹션(코스피·코스닥·채권)의 정규 시간이 다를 수 있음 — MVP는 09:00~15:30 고정.
 * [수정 시 고려사항] 연장 시간(pre-market 08:00~, after-market ~18:00) 필요 시 isWithinExtendedHours() 추가.
 *                  공휴일 판단은 공공 API(holidays) 또는 자체 테이블로 확장 가능.
 */
public final class TradingHoursUtil {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalTime MARKET_OPEN  = LocalTime.of(9, 0);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

    private TradingHoursUtil() {
    }

    /**
     * 주어진 시각이 KRX 정규 거래 시간(월~금 09:00~15:30 KST) 내인지 판단.
     * 토·일은 항상 false. 공휴일은 판단하지 않음(MVP 제약).
     */
    public static boolean isWithinTradingHours(ZonedDateTime dateTime) {
        ZonedDateTime kst = dateTime.withZoneSameInstant(KST);
        DayOfWeek dow = kst.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime time = kst.toLocalTime();
        return !time.isBefore(MARKET_OPEN) && !time.isAfter(MARKET_CLOSE);
    }

    /** 현재 시각(KST 기준) 기준으로 KRX 정규 거래 시간인지 판단. */
    public static boolean isWithinTradingHoursNow() {
        return isWithinTradingHours(ZonedDateTime.now(KST));
    }
}
