// [목적] 포트폴리오(보유 종목) API 타입 + TanStack Query 훅
// [이유] 종목 목록·등록·수정·삭제를 서버 상태로 관리
// [사이드 임팩트] 종목 등록 후 ["portfolios"] 쿼리 무효화 필요
// [수정 시 고려사항] avg_buy_price·quantity는 서버에서 AES-256 복호화 후 평문 반환.
//   클라이언트 로그 출력 금지(console.log 사용 자제)

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface Portfolio {
  id: number;
  stock_code: string;
  corp_name: string;
  avg_buy_price: number;
  quantity: number;
  memo?: string;
  notify_enabled: boolean;
  created_at: string;
}

export interface CreatePortfolioBody {
  stock_code: string;
  avg_buy_price?: number;
  quantity?: number;
  memo?: string;
}

export function usePortfolios() {
  return useQuery({
    queryKey: ["portfolios"],
    queryFn: () => apiClient<Portfolio[]>("/portfolios"),
  });
}

export function useCreatePortfolio() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreatePortfolioBody) =>
      apiClient<Portfolio>("/portfolios", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["portfolios"] }),
  });
}

export function useDeletePortfolio() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiClient(`/portfolios/${id}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["portfolios"] }),
  });
}
