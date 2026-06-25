"use client";

// [목적] Asia/Seoul 기준 오늘 날짜 문자열(YYYY-MM-DD)을 자정에 자동 갱신하는 훅
// [이유] dashboard/page.tsx에서 today를 렌더 시점에만 계산하면 자정 후 브라우저를 열어둔 경우
//   어제 날짜로 고착되어 BE 조회 범위가 어긋남.
// [사이드 임팩트] useEffect 내 setTimeout이 자정까지의 잔여 시간(ms)에 한 번 실행됨.
//   today 값이 바뀌면 useEffect가 재실행되어 다음 자정용 타이머를 다시 등록 — 무한 루프 없음.
// [수정 시 고려사항] "sv" locale은 스웨덴어가 아닌 ISO 8601 날짜 포맷(YYYY-MM-DD) 출력용 관용 트릭.
//   DST가 없는 Asia/Seoul에서는 실제 오프셋 변동이 없어 +100ms 여유값으로 충분.

import { useState, useEffect } from "react";

function getSeoulToday(): string {
  return new Intl.DateTimeFormat("sv", { timeZone: "Asia/Seoul" }).format(new Date());
}

export function useTodaySeoul(): string {
  const [today, setToday] = useState(getSeoulToday);

  useEffect(() => {
    const now = new Date();
    const seoulNow = new Date(now.toLocaleString("en-US", { timeZone: "Asia/Seoul" }));
    const msUntilMidnight =
      new Date(seoulNow.getFullYear(), seoulNow.getMonth(), seoulNow.getDate() + 1).getTime() -
      seoulNow.getTime();
    const timer = setTimeout(() => setToday(getSeoulToday()), msUntilMidnight + 100);
    return () => clearTimeout(timer);
  }, [today]);

  return today;
}
