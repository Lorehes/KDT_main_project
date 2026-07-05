// [목적] YYYY-MM-DD 날짜 문자열을 ±N일 이동한 YYYY-MM-DD 문자열로 변환하는 순수 함수
// [이유] "최근 N일" 조회 범위(portfolios 5일, dashboard 3일)의 from 계산용.
//   useTodaySeoul()이 주는 Asia/Seoul 기준 문자열을 입력으로 받아 파생하므로 TZ 개념이 재등장하지 않음 —
//   기존 getWeekRange()류의 로컬 TZ Date 산술(UTC toISOString과 어긋남) 결함을 반복하지 않기 위한 공유 유틸.
// [사이드 임팩트] portfolios/page.tsx(최근 5일)·dashboard/page.tsx(최근 3일)가 사용.
//   Date.UTC 산술이라 실행 환경 타임존과 무관하게 결정적(달/연 경계·윤년 오버플로 자동 처리).
// [수정 시 고려사항] 입력은 YYYY-MM-DD 형식 전제(useTodaySeoul 반환 형식) — 형식 검증은 하지 않음(내부 유틸).
//   DART rcept_dt(YYYYMMDD)를 직접 넣으면 안 됨 — 하이픈 형식으로 정규화 후 사용할 것.

export function shiftDateStr(dateStr: string, days: number): string {
  const [y, m, d] = dateStr.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
