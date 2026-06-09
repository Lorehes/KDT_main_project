// [목적] 종목 검색 API 타입 + TanStack Query 훅
// [이유] StockSearchCombobox의 실시간 자동완성에 사용
// [사이드 임팩트] PUBLIC 엔드포인트이므로 인증 없이 호출 가능
// [수정 시 고려사항] debounce는 컴포넌트 레벨에서 처리. enabled: q.length >= 1로 빈 검색 방지

import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface StockSearchResult {
  stock_code: string;
  corp_name: string;
  market: "KOSPI" | "KOSDAQ";
}

export function useStockSearch(q: string) {
  return useQuery({
    queryKey: ["stocks-search", q],
    queryFn: () =>
      apiClient<StockSearchResult[]>(`/stocks/search?q=${encodeURIComponent(q)}`),
    enabled: q.trim().length >= 1,
    staleTime: 30_000,
  });
}
