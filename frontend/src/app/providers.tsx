"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";

// 전역 클라이언트 상태/서버 페칭 Provider (design_structure 4장).
// 서버 상태는 TanStack Query, 클라이언트 상태는 Zustand(스토어는 기능 구현 시 추가).
export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: { staleTime: 60_000, retry: 1 },
        },
      }),
  );

  return <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>;
}
