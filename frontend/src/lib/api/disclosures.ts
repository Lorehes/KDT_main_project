// [목적] 공시·분석 API 타입 정의 + TanStack Query 훅
// [이유] 서버 상태(공시 목록·상세·분석)를 TanStack Query로 캐싱·무효화
// [사이드 임팩트] 대시보드·공시 피드·공시 상세 페이지가 이 훅을 사용
// [수정 시 고려사항] 티어 미달 필드는 API에서 제외되어 반환됨(undefined). TierGate 컴포넌트가 처리

import { useQuery, useMutation } from "@tanstack/react-query";
import { apiClient } from "./client";

// ─── 타입 ───────────────────────────────────────────────────────────────────

export type Sentiment = "POSITIVE" | "NEUTRAL" | "NEGATIVE";
export type ExpectedReaction = "UP" | "FLAT" | "DOWN";

export interface Disclosure {
  id: number;
  rcept_no: string;
  corp_name: string;
  stock_code: string;
  report_nm: string;
  rcept_dt: string;
  attachment_url?: string;
  sentiment?: Sentiment;
  confidence?: number;
  is_withheld?: boolean;
  summary?: string;
}

export interface SimilarDisclosure {
  rcept_no: string;
  corp_name: string;
  rcept_dt: string;
  price_reaction_5d_pct: number;
}

export interface DisclosureAnalysis {
  analysis_id: number;
  disclosure_id: number;
  sentiment: Sentiment;
  confidence: number;
  is_withheld: boolean;
  summary: string;
  stage_reached: number;
  // Pro 이상
  expected_reaction?: ExpectedReaction;
  rationale?: string;
  similar_disclosures?: SimilarDisclosure[];
  // Premium 이상
  financial_context?: Record<string, unknown>;
  disclaimer: string;
  report_inaccuracy_path: string;
  created_at: string;
}

export interface DisclosurePage {
  content: Disclosure[];
  page: { number: number; size: number; total_elements: number; total_pages: number };
}

export interface DisclosureListParams {
  scope?: "portfolio" | "all";
  stock_code?: string;
  sentiment?: Sentiment;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// ─── 훅 ─────────────────────────────────────────────────────────────────────

export function useDisclosures(params: DisclosureListParams = {}) {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([k, v]) => v !== undefined && query.set(k, String(v)));

  return useQuery({
    queryKey: ["disclosures", params],
    queryFn: () => apiClient<DisclosurePage>(`/disclosures?${query}`),
  });
}

export function useDisclosure(id: number) {
  return useQuery({
    queryKey: ["disclosures", id],
    queryFn: () => apiClient<Disclosure>(`/disclosures/${id}`),
    enabled: !!id,
  });
}

export function useDisclosureAnalysis(disclosureId: number) {
  return useQuery({
    queryKey: ["analysis", disclosureId],
    queryFn: () => apiClient<DisclosureAnalysis>(`/disclosures/${disclosureId}/analysis`),
    enabled: !!disclosureId,
  });
}

export function useFeedbackMutation(analysisId: number) {
  return useMutation({
    mutationFn: (body: { verdict: "USEFUL" | "INACCURATE"; reason?: string }) =>
      apiClient(`/analyses/${analysisId}/feedback`, {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}
