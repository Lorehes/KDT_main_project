// [목적] Sheet 컴포넌트의 side("bottom"|"right") 값을 뷰포트 폭에 따라 반환하는 커스텀 훅
// [이유] signup/complete·notifications 두 페이지에서 동일한 matchMedia useEffect가 중복 — 단일 훅으로 추출
// [사이드 임팩트] SM_BREAKPOINT(640px) 상수를 훅 내부에서 관리 — 변경 시 이 파일만 수정하면 전체 적용
//   BOTTOM_SHEET_MIN_HEIGHT 상수도 여기서 export — PortfolioSheet min-h 오버라이드 호출부에서 참조
// [수정 시 고려사항] Tailwind 기준 sm breakpoint 변경 시 SM_BREAKPOINT 값을 tailwind.config와 동기화.
//   SSR(서버 사이드 렌더) 시 window 미존재 — useState 초기값 "bottom" 으로 hydration mismatch 방지(클라이언트에서 즉시 수정됨).

import { useEffect, useState } from "react";

const SM_BREAKPOINT = "(min-width: 640px)";

/** 모바일 바텀 시트 최소 높이 — notifications/page.tsx 등 bottom side 오버라이드 시 사용 */
export const BOTTOM_SHEET_MIN_HEIGHT = "min-h-[66dvh]";

/** 뷰포트 sm(640px) 기준으로 Sheet side 자동 전환 — 모바일: bottom, 데스크톱: right */
export function useSheetSide(): "bottom" | "right" {
  // 클라이언트에서 즉시 정확한 값 산출 — useEffect 지연 없이 첫 렌더부터 올바른 side 반환
  const [side, setSide] = useState<"bottom" | "right">(() =>
    typeof window === "undefined"
      ? "bottom"
      : window.matchMedia(SM_BREAKPOINT).matches ? "right" : "bottom"
  );

  useEffect(() => {
    const mq = window.matchMedia(SM_BREAKPOINT);
    const update = () => setSide(mq.matches ? "right" : "bottom");
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);

  return side;
}
