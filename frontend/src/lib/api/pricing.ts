// [목적] 요금제 API 클라이언트 — GET /api/v1/pricing/plans
// [이유] PricingClient.tsx의 정적 PLANS 배열을 BE application.yml 기반 동적 응답으로 교체.
//   BE 재기동으로 가격 변경 시 FE 코드 수정 없이 반영. staleTime 60초 — 정적에 가까운 데이터.
// [사이드 임팩트] BE가 offline이면 정적 fallback 없이 로딩 상태로 대기 — 운영 환경에서 가용성 주의.
// [수정 시 고려사항] features 배열 내용은 CLAUDE.md §7 투자 권유 표현 금지 적용 대상.

import { useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface PricingPlan {
  tier: string;
  price: number;
  currency: string;
  features: string[];
  recommended_for: string;
  monthly_free_quota: number;
}

export function usePricingPlans() {
  return useQuery({
    queryKey: ["pricing-plans"],
    queryFn: () => apiClient<PricingPlan[]>("/pricing/plans"),
    staleTime: 60_000,
  });
}
