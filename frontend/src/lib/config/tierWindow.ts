// [목적] 티어 날짜 창 관련 FE 표시 상수 단일 소스 — Free 조회 창 폴백 + 대시보드 피드 표시 창
// [이유] 과거 portfolios(RECENT_DISCLOSURE_DAYS=5)·dashboard(RECENT_FEED_DAYS=3)에 리터럴로 흩어져 있던
//   날짜 창 상수를 한 곳으로 모음(tier-policy-config-api). Free 창 실값은 BE(/pricing/plans recent_window_days)가
//   단일 소스이며, 여기 FREE_RECENT_WINDOW_DAYS는 API 응답 도착 전 초기 렌더용 폴백일 뿐이다.
// [사이드 임팩트] portfolios/page.tsx(Free 창 파생 폴백)·dashboard/page.tsx(피드 표시 창)가 사용.
//   불변식 DASHBOARD_FEED_DAYS ≤ FREE_RECENT_WINDOW_DAYS는 tierWindow.test.ts가 강제(회귀 안전망, Phase 1).
// [수정 시 고려사항] FREE_RECENT_WINDOW_DAYS는 BE yml pricing.plans FREE recent-window-days(현재 5)와 반드시 일치시킬 것
//   — 폴백값이 실제 창보다 크면 API 로드 전 잠깐 라벨-데이터가 어긋날 수 있음.
//   DASHBOARD_FEED_DAYS는 순수 UI 표시 선택(Free 창과 무관, 창 이하이기만 하면 됨) — API 파생 대상 아님.

/**
 * Free 티어 조회 가능 최근 N일 — **폴백 전용**.
 * 실제 창은 BE /pricing/plans의 FREE recent_window_days가 단일 소스. API 로드 전 초기값으로만 사용.
 */
export const FREE_RECENT_WINDOW_DAYS = 5;

/** 대시보드 공시 피드 표시 창(오늘 포함 일수). UI 표시 선택 — Free 창 이하여야 함(불변식은 테스트가 강제). */
export const DASHBOARD_FEED_DAYS = 3;
