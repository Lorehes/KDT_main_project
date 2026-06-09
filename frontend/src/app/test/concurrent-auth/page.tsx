// [목적] R10 Playwright 테스트 전용 픽스처 페이지 — 동시 401 및 BroadcastChannel 시나리오 실행
// [이유] apiClient는 모듈 수준 함수이므로 브라우저 컨텍스트 안에서 실제로 호출해야 Promise 큐 동작을 검증 가능.
//   page.evaluate()로 fetch를 직접 호출하면 apiClient 코드 경로를 우회하게 됨.
// [사이드 임팩트] NODE_ENV=production 이면 null 반환(미들웨어로 차단 불가한 경우 대비).
//   AuthBroadcastListener를 직접 포함 — (app) 그룹 밖이므로 layout.tsx에서 자동 마운트 안 됨.
// [수정 시 고려사항] 이 페이지는 e2e 테스트 외 목적으로 사용하지 않는다.
//   concurrent 파라미터가 없으면 API 호출을 생략해 테스트 간섭 방지.

"use client";

import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { apiClient } from "@/lib/api/client";
import { AuthBroadcastListener } from "@/components/layout/AuthBroadcastListener";

interface MeResponse {
  id: number;
}

export default function ConcurrentAuthTestPage() {
  const [status, setStatus] = useState("pending");
  const searchParams = useSearchParams();
  const mode = searchParams.get("mode");

  useEffect(() => {
    if (process.env.NODE_ENV === "production") return;
    if (mode !== "concurrent") return;

    Promise.allSettled(
      Array.from({ length: 5 }, () => apiClient<MeResponse>("/users/me")),
    ).then((results) => {
      const succeeded = results.filter((r) => r.status === "fulfilled").length;
      const failed = results.filter((r) => r.status === "rejected").length;
      setStatus(`done:${succeeded}:${failed}`);
    });
  }, [mode]);

  if (process.env.NODE_ENV === "production") return null;

  return (
    <>
      <AuthBroadcastListener />
      <div data-testid="status">{status}</div>
    </>
  );
}
