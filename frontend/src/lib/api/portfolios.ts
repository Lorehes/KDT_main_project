// [목적] 포트폴리오(보유 종목) API 타입 + TanStack Query 훅
// [이유] BE PortfolioController는 PUT /{id}로 수정(api_spec §2.2는 PATCH 명세이나 BE 구현이 PUT — FE 동기화).
//   요청 body는 snake_case(stock_code, avg_buy_price) — BE PortfolioRequest @JsonProperty로 매핑됨.
//   매수가·수량은 BE에서 AES-256 복호화 후 BigDecimal로 반환 — 클라이언트 로그 절대 금지
// [사이드 임팩트] 생성·수정·삭제·일괄등록 모두 ["portfolios"] 쿼리 무효화로 자동 리페치.
//   useDeletePortfolio·useUpdatePortfolio onError → Sonner toast.error 발화.
//   useCreatePortfolio는 portfolios/new에서 mutateAsync+try/catch로 폼 에러 처리 — onError 없음.
//   importPortfolios()는 함수(훅 아님) — useCallback 내부에서 호출, 결과로 toast 메시지 구성.
//   staleTime: 2분 + refetchOnWindowFocus:true — 포트폴리오 변경은 즉시성 필요. BE @CacheEvict로 서버 캐시도 즉시 무효화됨.
//   usePortfolioSummary staleTime: 5분 — KrxPriceSyncJob 일 1회 배치(18:00 KST) 기준. 창 포커스 리패치 활성.
// [수정 시 고려사항] corp_name은 nullable 유지 — stocks 마스터 미등재 엣지케이스 대비(BE corpName: null 허용).
//   staleTime 연장 시 알림 설정 변경 후 목록 반영 지연 가능 — invalidateQueries로 강제 무효화 가능.
//   ImportPortfoliosResult 카테고리 변경 시 BE BulkImportResult + FE toast 분기(portfolios/new/page.tsx)도 동기화.
//   2026-06-26 Lorehes: ImportPortfoliosResult 인터페이스 + importPortfolios() 함수 추가 — CSV 일괄 등록.

import { useQuery, useMutation, useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { apiClient, ApiException } from "./client";

export interface Portfolio {
  id: number;
  stock_code: string;
  corp_name?: string;      // nullable — BE stocks 마스터 미등재 엣지케이스 대비
  avg_buy_price?: number;  // BigDecimal → number (AES-256 복호화 후 반환)
  quantity?: number;
  close_price?: number;    // 최신 종가(공개 시세). KRX 일배치 미수집 시 undefined (Wave 3)
  price_asof?: string;     // 종가 기준일 YYYY-MM-DD. close_price와 함께 null 가능
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

/** GET /api/v1/portfolios/summary 응답 — KRX 종가 기반 평가 손익 집계. */
export interface PortfolioSummary {
  /** 총 매수금액 (close_price 있는 종목만 합산). null이면 데이터 없음 */
  total_cost_basis: number;
  total_eval_amount: number;
  total_pnl: number;
  /** 수익률(%). total_cost_basis == 0 이면 null. */
  pnl_rate: number | null;
  /** 종가 수집 완료 종목 수 */
  priced_count: number;
  /** 종가 미수집 또는 매수가/수량 미입력 종목 수 */
  unpriced_count: number;
  /** 종가 기준일 (YYYY-MM-DD). priced_count == 0이면 null. */
  as_of: string | null;
}

export function usePortfolioSummary() {
  return useQuery({
    queryKey: ["portfolios", "summary"],
    queryFn: () => apiClient<PortfolioSummary>("/portfolios/summary"),
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: true,
  });
}

export function usePortfolios() {
  return useQuery({
    queryKey: ["portfolios"],
    queryFn: () => apiClient<Portfolio[]>("/portfolios"),
    staleTime: 2 * 60_000,
    refetchOnWindowFocus: true,
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

/** POST /api/v1/portfolios/import 벌크 등록 응답 — added/skipped_* 길이로 토스트 메시지 구성. */
export interface ImportPortfoliosResult {
  added: string[];
  skipped_duplicate: string[];
  skipped_unsupported: string[];
  skipped_limit: string[];
}

/**
 * CSV 종목코드 일괄 등록 — stock_codes만 전송, avg_buy_price/quantity 절대 포함 금지(CLAUDE.md §7).
 * 빈 배열 또는 50개 초과 시 BE에서 HTTP 400 반환.
 */
export async function importPortfolios(stockCodes: string[]): Promise<ImportPortfoliosResult> {
  return apiClient<ImportPortfoliosResult>("/portfolios/import", {
    method: "POST",
    body: JSON.stringify({ stock_codes: stockCodes }),
  });
}
