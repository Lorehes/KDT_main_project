// [목적] 티어 날짜 창 불변식 회귀 안전망(Phase 1) — 대시보드 표시 창 ≤ Free 조회 창
// [이유] 대시보드가 Free 창보다 넓은 날짜를 표시하면 Free 사용자에게 라벨-데이터 불일치 발생.
//   BE↔FE 크로스-런타임 강제는 불가하나(별도 빌드), 최소한 FE 내부 표시 상수 불변식은 컴파일/테스트로 고정.
// [사이드 임팩트] 없음 (상수 단위 테스트)
// [수정 시 고려사항] 값 변경 시 BE yml pricing.plans FREE recent-window-days와의 정합도 함께 확인(PricingIntegrationTest)

import { describe, it, expect } from "vitest";
import { FREE_RECENT_WINDOW_DAYS, DASHBOARD_FEED_DAYS } from "./tierWindow";

describe("tier window 불변식", () => {
  it("대시보드 피드 창 ≤ Free 조회 창", () => {
    expect(DASHBOARD_FEED_DAYS).toBeLessThanOrEqual(FREE_RECENT_WINDOW_DAYS);
  });

  it("두 창 모두 양수", () => {
    expect(FREE_RECENT_WINDOW_DAYS).toBeGreaterThan(0);
    expect(DASHBOARD_FEED_DAYS).toBeGreaterThan(0);
  });
});
