// [목적] DART 공시 접수일(rcept_dt)을 사람이 읽는 표시 문자열과 ISO 날짜로 변환하는 순수 함수 모음
// [이유] DisclosureCard는 rcept_dt(YYYYMMDD)를 가공 없이 노출("20260705")하고, portfolios 패널은
//   자체 formatRelativeTime으로 "N시간 전"을 렌더 → 같은 데이터가 화면마다 다른 포맷(표기 이원화).
//   portfolios의 로직을 이 공용 유틸로 승격해 대시보드·공시 피드·포트폴리오가 단일 함수를 재사용한다.
//   (disclosure-date-format-unify Spec 카드 #1)
// [사이드 임팩트] DisclosureCard·portfolios/page.tsx·disclosures/page.tsx(그룹 라벨)가 사용.
//   상대시간이 Date.now()에 의존하므로 순수성·테스트 결정성을 위해 now를 주입 가능한 2번째 인자로 노출.
//   7일 초과 공시는 상대시간("30일 전")보다 절대날짜가 읽기 쉬워 YYYY-MM-DD로 폴백(임계값 RELATIVE_MAX_DAYS).
// [수정 시 고려사항] 입력은 DART rcept_dt(YYYYMMDD) 또는 ISO 문자열 — 두 형식만 전제(BE 계약).
//   BE는 rcept_dt를 YYYYMMDD로 유지하는 것이 계약이므로 원본 값은 변형하지 않고 표시 계층에서만 변환한다
//   (design_structure §241 "원문 날짜 원본 렌더"는 LLM 팩트 변형 금지 규칙 — 표시 포맷팅은 값 불변이라 무관).
//   <time> 요소의 dateTime 속성은 toIsoDate로 ISO를 유지해 기계 판독·접근성을 보존할 것.
//   RELATIVE_MAX_DAYS 변경 시 formatDisclosureDate.test.ts 폴백 경계 케이스도 동기화.

/** 상대시간 표기를 유지하는 최대 일수. 초과 시 절대날짜(YYYY-MM-DD)로 폴백. */
const RELATIVE_MAX_DAYS = 7;

/**
 * rcept_dt(YYYYMMDD 또는 ISO)를 `YYYY-MM-DD` ISO 날짜 문자열로 정규화.
 * `<time dateTime={...}>` 속성·정렬·비교의 기계 판독 소스로 사용.
 */
export function toIsoDate(rceptDt: string): string {
  if (/^\d{8}$/.test(rceptDt)) {
    return `${rceptDt.slice(0, 4)}-${rceptDt.slice(4, 6)}-${rceptDt.slice(6, 8)}`;
  }
  // 이미 ISO(날짜/일시)면 앞 10자(YYYY-MM-DD)만 취함
  return rceptDt.slice(0, 10);
}

/**
 * rcept_dt를 사람이 읽는 표시 문자열로 변환.
 * - 24시간 이내: "방금 전" / "N분 전" / "N시간 전"
 * - 7일 이내: "N일 전"
 * - 7일 초과: "YYYY-MM-DD" (절대날짜 — 오래된 공시 가독성)
 * @param now 기준 시각(ms). 테스트 결정성을 위해 주입 가능. 미지정 시 Date.now().
 */
export function formatDisclosureDate(rceptDt: string, now: number = Date.now()): string {
  const isoDate = toIsoDate(rceptDt);
  // rcept_dt는 KST(Asia/Seoul) 달력일 → 상대시간도 KST 자정 기준으로 계산.
  // new Date("2026-07-05")는 UTC 자정(=09:00 KST)으로 파싱돼 KST 00:00~09:00엔 당일 공시가 음수 diff(절대날짜)로
  // 튀는 문제가 있어, 오프셋(+09:00)을 명시해 KST 자정에 앵커한다.
  const diffMs = now - new Date(`${isoDate}T00:00:00+09:00`).getTime();

  // 미래 날짜(음수 diff)는 상대시간이 의미 없어 절대날짜로 표기
  if (diffMs < 0) return isoDate;

  const min = Math.floor(diffMs / 60_000);
  if (min < 1) return "방금 전";
  if (min < 60) return `${min}분 전`;

  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}시간 전`;

  const days = Math.floor(hr / 24);
  if (days <= RELATIVE_MAX_DAYS) return `${days}일 전`;

  return isoDate;
}
