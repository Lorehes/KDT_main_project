"use client";

// [목적] 전역 Provider 트리 — TanStack Query + Sonner Toast
// [이유] QueryClientProvider는 앱 전역 서버 상태 관리. Toaster는 mutation onError Toast를 전역 렌더링.
//   단일 Toaster 인스턴스만 마운트해야 중복 토스트 방지(R10, frontend-api-integration).
// [사이드 임팩트] Toaster 위치 변경 시 z-index 충돌 가능(modal/drawer 위). richColors로 sentiment 색상 일치.
// [수정 시 고려사항] Toaster theme prop으로 다크/라이트 모드 연동 가능. position은 bottom-right 고정(모바일 안전권).

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { Toaster } from "sonner";

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { staleTime: 60_000, retry: 1 },
        },
      }),
  );

  return (
    <QueryClientProvider client={queryClient}>
      {children}
      <Toaster position="bottom-right" richColors closeButton />
    </QueryClientProvider>
  );
}
