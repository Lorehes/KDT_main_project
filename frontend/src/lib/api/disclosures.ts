// [목적] 공시·분析 API 타입 정의 + TanStack Query 훅
// [이유] 서버 상태(공시 목록·상세·분析)를 TanStack Query로 캐싱·무효화
// [사이드 임팩트] 대시보드·공시 피드·공시 상세 페이지가 이 훅을 사용.
//   useDisclosureAnalysis: opts.enabled(R7) + 404 retry 차단 추가 — 분析 미완료 공시에 즉시 "분析 대기 중" 표시.
//   useDisclosure/useDisclosureAnalysis: refetchOnWindowFocus=false — 공시 상세는 거의 불변, 포커스 복귀 시 불필요 refetch 차단.
//   useDisclosures: staleTime 60s — 피드 목록은 60초 내 신규 공시 추가 허용 범위로 판단.
//   DisclosureListParams.q 추가: queryKey에 params 객체 전체가 포함되므로 q 변경 시 자동으로 다른 캐시 키 생성.
// [수정 시 고려사항] 티어 미달 필드는 API에서 제외되어 반환됨(undefined). TierGate 컴포넌트가 처리.
//   useDisclosureAnalysis의 404 retry=false: 분析 미완료(정상)는 재시도 않음. 5xx 등 실 오류는 3회 재시도.
//   providers.tsx의 전역 staleTime이 제거됨 — 각 훅의 명시적 staleTime 필수.
//   q 빈 문자열은 URLSearchParams에 "q=" 로 전송 — BE에서 isBlank() 처리로 필터 없음 보장.

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

// v2 (analysis-stage3-rag-chroma): priceReaction5dPct 제거(KRX 주가 API 미구현) → 유사도 기반.
// KRX 시계열 주가 구현 시 별도 v3에서 일자별 반응 필드 재추가 예정([[disclosure-detail-redesign]] 예측차트 Spec).
export interface SimilarDisclosure {
  disclosure_id: number;
  rcept_no: string;
  corp_name: string;
  corp_code: string;
  disclosure_type: string;
  rcept_dt: string;
  similarity_score: number;
}

export interface DisclosureAnalysis {
  analysis_id: number;
  disclosure_id: number;
  sentiment: Sentiment;
  confidence: number;
  is_withheld: boolean;
  summary: string;
  // Free (Stage 2) — 비어있으면 BE가 필드 자체를 생략(NON_NULL). 구버전 분석도 미포함.
  key_points?: string[];
  positive_factors?: string[];
  negative_factors?: string[];
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
  /** 공시 제목·회사명 키워드 검색 (R1). 빈 문자열은 서버에서 null 처리. */
  q?: string;
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
  Object.entries(params).forEach(([k, v]) => {
    if (v === undefined) return;
    // 빈 문자열 q는 전송 생략 — BE에서도 isBlank() 처리하나 불필요한 param 제거
    if (k === "q" && String(v).trim() === "") return;
    query.set(k, String(v));
  });

  return useQuery({
    queryKey: ["disclosures", params],
    queryFn: () => apiClient<DisclosurePage>(`/disclosures?${query}`),
    staleTime: 60_000,
  });
}

export function useDisclosure(id: number) {
  return useQuery({
    queryKey: ["disclosures", id],
    queryFn: () => apiClient<Disclosure>(`/disclosures/${id}`),
    enabled: !!id,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
  });
}

export function useDisclosureAnalysis(disclosureId: number, opts?: { enabled?: boolean }) {
  return useQuery({
    queryKey: ["analysis", disclosureId],
    queryFn: () => apiClient<DisclosureAnalysis>(`/disclosures/${disclosureId}/analysis`),
    enabled: (opts?.enabled ?? true) && !!disclosureId,
    staleTime: 5 * 60_000,
    refetchOnWindowFocus: false,
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
