// [목적] shiftDateStr 경계 케이스 검증 — 달/연 경계, 윤년, 0일 항등
// [이유] "최근 N일" from 계산이 월초·연초에 어긋나면 조용히 조회 범위가 틀어짐(회귀: 날짜 필터 오류는 UI에서 감지 어려움)
// [사이드 임팩트] 없음 (순수 함수 단위 테스트)
// [수정 시 고려사항] shiftDateStr 입력 형식(YYYY-MM-DD) 전제가 바뀌면 케이스 추가

import { describe, it, expect } from "vitest";
import { shiftDateStr } from "./shiftDateStr";

describe("shiftDateStr", () => {
  it("같은 달 내 이동", () => {
    expect(shiftDateStr("2026-07-05", -4)).toBe("2026-07-01");
  });

  it("월 경계를 넘는 음수 이동", () => {
    expect(shiftDateStr("2026-07-02", -4)).toBe("2026-06-28");
  });

  it("연 경계를 넘는 음수 이동", () => {
    expect(shiftDateStr("2026-01-02", -4)).toBe("2025-12-29");
  });

  it("윤년 2월 경계 (2024-03-01 - 2일 = 2024-02-28)", () => {
    expect(shiftDateStr("2024-03-01", -2)).toBe("2024-02-28");
  });

  it("평년 2월 경계 (2026-03-01 - 2일 = 2026-02-27)", () => {
    expect(shiftDateStr("2026-03-01", -2)).toBe("2026-02-27");
  });

  it("양수 이동 (월 경계)", () => {
    expect(shiftDateStr("2026-06-29", 3)).toBe("2026-07-02");
  });

  it("0일 이동은 항등", () => {
    expect(shiftDateStr("2026-07-05", 0)).toBe("2026-07-05");
  });
});
