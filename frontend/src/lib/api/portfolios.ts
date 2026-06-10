// [목적] 포트폴리오(보유 종목) API 타입 + TanStack Query 훅
// [이유] BE PortfolioController는 PUT /{id}로 수정(api_spec §2.2는 PATCH 명세이나 BE 구현이 PUT — FE 동기화).
//   요청 body는 snake_case(stock_code, avg_buy_price) — BE PortfolioRequest @JsonProperty로 매핑됨.
//   매수가·수량은 BE에서 AES-256 복호화 후 BigDecimal로 반환 — 클라이언트 로그 절대 금지
// [사이드 임팩트] 생성·수정·삭제 모두 ["portfolios"] 쿼리 무효화로 자동 리페치.
//   useDeletePortfolio·useUpdatePortfolio onError → Sonner toast.error 발화.
//   useCreatePortfolio는 portfolios/new에서 mutateAsync+try/catch로 폼 에러 처리 — onError 없음.
// [수정 시 고려사항] BE PortfolioResponse에 corp_name 없음 — optional 처리. stocks JOIN 추가 후 non-optional 가능

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { apiClient, ApiException } from "./client";

export interface Portfolio {
  id: number;
  stock_code: string;
  corp_name?: string;      // BE PortfolioResponse 미포함 — stocks JOIN 후 추가 예정
  avg_buy_price?: number;  // BigDecimal → number (AES-256 복호화 후 반환)
  quantity?: number;
  memo?: string;
  created_at: string;
  updated_at?: string;
}

export interface CreatePortfolioBody {
  stock_code: string;
  avg_buy_price?: number;
  quantity?: number;
  memo?: string;
}

export type UpdatePortfolioBody = Omit<CreatePortfolioBody, "stock_code">;

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

export function useUpdatePortfolio() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpdatePortfolioBody }) =>
      apiClient<Portfolio>(`/portfolios/${id}`, { method: "PUT", body: JSON.stringify(body) }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["portfolios"] }),
    onError: (err) =>
      toast.error(err instanceof ApiException ? err.body.message : "종목 수정에 실패했습니다."),
  });
}

export function useDeletePortfolio() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => apiClient(`/portfolios/${id}`, { method: "DELETE" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["portfolios"] }),
    onError: (err) =>
      toast.error(err instanceof ApiException ? err.body.message : "종목 삭제에 실패했습니다."),
  });
}
