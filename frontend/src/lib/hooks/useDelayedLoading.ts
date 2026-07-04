// [목적] 200ms 지연 후에만 Skeleton 표시 — 빠른 로딩(< 200ms)에서 Skeleton 깜박임 방지
// [이유] R8: 200ms 이내 완료 시 Skeleton 미표시. 느린 연결에서는 피드백 필요 — delayMs 후 true 반환
// [사이드 임팩트] isLoading false 전환 시 즉시 false 반환 (불필요한 Skeleton 지속 없음)
// [수정 시 고려사항] delayMs 기본값 200. 네트워크 환경에 따라 호출 측에서 조정 가능

import { useEffect, useState } from "react";

export function useDelayedLoading(isLoading: boolean, delayMs = 200): boolean {
  const [show, setShow] = useState(false);

  useEffect(() => {
    if (!isLoading) {
      setShow(false);
      return;
    }
    const t = setTimeout(() => setShow(true), delayMs);
    return () => clearTimeout(t);
  }, [isLoading, delayMs]);

  return show;
}
