// [목적] 사용자 티어 체크 훅 — isPro/isPremium/tier 3값을 한 번에 제공
// [이유] portfolios·disclosures·disclosures/[id]·Sidebar 4곳에 동일한 isPro 계산이 중복됨(R7).
//   한 곳에서 관리해 BM 정책 변경(예: PRO→PREMIUM 병합) 시 단일 수정 지점 확보.
// [사이드 임팩트] useAuthStore 의존 → 클라이언트 컴포넌트('use client')에서만 동작.
//   SSR server component에서는 사용 불가 — 현재 (app)/* 페이지는 모두 client component.
// [수정 시 고려사항] 새 티어 추가 시 이 훅 + BE shared/enums/Tier.java 동시 갱신 필요.

import { useAuthStore } from "@/lib/stores/authStore";

export type Tier = "FREE" | "PRO" | "PREMIUM";

const VALID_TIERS = new Set<Tier>(["FREE", "PRO", "PREMIUM"]);

// NOTE: 이 훅은 UI 표시 제어 전용입니다. 데이터 접근 제어는 반드시 BE에서 수행합니다.
export function useTierCheck() {
  const { user } = useAuthStore();
  const raw = user?.tier ?? "FREE";
  const tier: Tier = VALID_TIERS.has(raw as Tier) ? (raw as Tier) : "FREE";
  return {
    tier,
    isPro:     tier === "PRO" || tier === "PREMIUM",
    isPremium: tier === "PREMIUM",
  };
}
