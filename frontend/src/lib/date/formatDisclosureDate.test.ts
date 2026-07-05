// [목적] formatDisclosureDate·toIsoDate 경계 검증 — 상대시간 구간·7일 폴백·미래·YYYYMMDD/ISO 정규화
// [이유] 날짜 표기 통일의 핵심 유틸. 상대시간이 Date.now() 의존이라 회귀가 조용히 발생 → now 주입으로 결정적 고정
// [사이드 임팩트] 없음 (순수 함수 단위 테스트)
// [수정 시 고려사항] RELATIVE_MAX_DAYS(7일) 변경 시 폴백 경계 케이스(7일 전/8일 절대) 동기화

import { describe, it, expect } from "vitest";
import { formatDisclosureDate, toIsoDate } from "./formatDisclosureDate";

describe("toIsoDate", () => {
  it("YYYYMMDD → YYYY-MM-DD", () => {
    expect(toIsoDate("20260705")).toBe("2026-07-05");
  });
  it("ISO 날짜는 앞 10자만", () => {
    expect(toIsoDate("2026-07-05")).toBe("2026-07-05");
    expect(toIsoDate("2026-07-05T09:30:00Z")).toBe("2026-07-05");
  });
});

// now는 KST 자정 앵커 기준으로 표기됨(오프셋 명시). rcept_dt "20260705"는 2026-07-05T00:00+09:00에 앵커.
const kst = (iso: string) => new Date(iso).getTime();

describe("formatDisclosureDate", () => {
  it("1분 미만 → 방금 전", () => {
    expect(formatDisclosureDate("20260705", kst("2026-07-05T00:00:30+09:00"))).toBe("방금 전");
  });

  it("1시간 미만 → N분 전", () => {
    expect(formatDisclosureDate("20260705", kst("2026-07-05T00:45:00+09:00"))).toBe("45분 전");
  });

  it("24시간 미만 → N시간 전", () => {
    expect(formatDisclosureDate("20260705", kst("2026-07-05T05:00:00+09:00"))).toBe("5시간 전");
  });

  it("KST 오전(자정~09시) 당일 공시도 상대시간 유지 (UTC 앵커 회귀 방지)", () => {
    // 2026-07-05 03:00 KST에 오늘 공시 조회 → 절대날짜가 아닌 "3시간 전"
    expect(formatDisclosureDate("20260705", kst("2026-07-05T03:00:00+09:00"))).toBe("3시간 전");
  });

  it("ISO 입력도 동일 처리", () => {
    expect(formatDisclosureDate("2026-07-05", kst("2026-07-05T03:00:00+09:00"))).toBe("3시간 전");
  });

  it("7일 이내 → N일 전", () => {
    // 2026-07-05 12:00 KST 기준, 2026-07-03 앵커 → 60시간 → 2일
    expect(formatDisclosureDate("20260703", kst("2026-07-05T12:00:00+09:00"))).toBe("2일 전");
  });

  it("정확히 7일 경계 → 7일 전 (포함)", () => {
    // 2026-06-28 앵커 → 180시간 = 7일 12시간 → floor 7일
    expect(formatDisclosureDate("20260628", kst("2026-07-05T12:00:00+09:00"))).toBe("7일 전");
  });

  it("7일 초과 → 절대날짜(YYYY-MM-DD) 폴백", () => {
    // 2026-06-27 앵커 → 204시간 = 8일 12시간 → 절대날짜
    expect(formatDisclosureDate("20260627", kst("2026-07-05T12:00:00+09:00"))).toBe("2026-06-27");
  });

  it("미래 날짜 → 절대날짜(상대시간 무의미)", () => {
    expect(formatDisclosureDate("20260706", kst("2026-07-05T12:00:00+09:00"))).toBe("2026-07-06");
  });
});
