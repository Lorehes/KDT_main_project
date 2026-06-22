// [목적] 공시·분析 API 타입 정의 + TanStack Query 훅
// [이유] 서버 상태(공시 목록·상세·분析)를 TanStack Query로 캐싱·무효화
// [사이드 임팩트] 대시보드·공시 피드·공시 상세 페이지가 이 훅을 사용.
//   useDisclosureAnalysis: opts.enabled(R7) + 404 retry 차단 추가 — 분析 미완료 공시에 즉시 "분析 대기 중" 표시.
// [수정 시 고려사항] 티어 미달 필드는 API에서 제외되어 반환됨(undefined). TierGate 컴포넌트가 처리.
//   useDisclosureAnalysis의 404 retry=false: 분析 미완료(정상)는 재시도 않음. 5xx 등 실 오류는 3회 재시도.

import { useQuery, useMutation } from "@tanstack/react-query";
import { apiClient, ApiException } from "./client";

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
  /** 판단 보류(is_withheld) 필터 — sentiment와 별개 플래그. true면 보류 공시만 반환. */
  withheld?: boolean;
  from?: string;
  to?: string;
  page?: number;
  size?: number;
  sort?: string;
}

// ─── 설정 ────────────────────────────────────────────────────────────────────

/**
 * expected_reaction 매직 문자열 → label·colorClass 매핑(R13).
 * disclosures/[id]/page.tsx에서 "UP"/"DOWN"/"FLAT" 인라인 비교를 제거.
 */
export const EXPECTED_REACTION_CONFIG: Record<ExpectedReaction, { label: string; colorClass: string }> = {
  UP:   { label: "▲ 상승 예상", colorClass: "bg-[color:var(--color-sentiment-positive)]/10 text-[color:var(--color-sentiment-positive)]" },
  DOWN: { label: "▼ 하락 예상", colorClass: "bg-[color:var(--color-sentiment-negative)]/10 text-[color:var(--color-sentiment-negative)]" },
  FLAT: { label: "― 보합 예상", colorClass: "bg-muted text-muted-foreground" },
};

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

export function useDisclosureAnalysis(disclosureId: number, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ["analysis", disclosureId],
    queryFn: () => apiClient<DisclosureAnalysis>(`/disclosures/${disclosureId}/analysis`),
    enabled: (opts?.enabled ?? true) && !!disclosureId,
    // 404 = 분析 미완료(정상) — 재시도하지 않음. 그 외 오류(5xx/네트워크)는 최대 3회 재시도
    retry: (count, err) => !(err instanceof ApiException && err.body.status === 404) && count < 3,
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
