"use client";

// [목적] 요금제 플랜 카드 그리드 — BE /pricing/plans API 연동 + 현재 사용자 등급 바인딩
// [이유] authStore(Zustand)는 클라이언트 전용이므로 page.tsx(서버 컴포넌트)와 분리.
//   API 응답으로 가격·기능 동적 렌더링 → BE application.yml 수정 후 재기동만으로 FE 변경 없이 반영.
// [사이드 임팩트] usePricingPlans staleTime 60초 — 1분 내 재진입 시 캐시 응답.
//   API 오류 시 FALLBACK_PLANS(기존 정적 데이터)로 graceful degradation.
// [수정 시 고려사항] features 배열은 API가 "포함" 항목만 반환 — 비포함 항목 표시가 필요하면 BE 스키마 확장.
//   결제 연동(W7) 전까지 Pro/Premium CTA는 /checkout으로 이동(mockup).

import { useRouter } from "next/navigation";
import { PlanCard, type PlanCardProps } from "@/components/domain/PlanCard";
import { useAuthStore } from "@/lib/stores/authStore";
import { usePricingPlans, type PricingPlan } from "@/lib/api/pricing";

const TIER_MAP: Record<string, string> = {
  FREE: "Free",
  PRO: "Pro",
  PREMIUM: "Premium",
};

const CTA_LABEL: Record<string, string> = {
  FREE: "무료로 시작하기",
  PRO: "7일 무료 체험 →",
  PREMIUM: "Premium 시작",
};

function planToCardProps(plan: PricingPlan): Omit<PlanCardProps, "isCurrent" | "onCta"> {
  const displayName = TIER_MAP[plan.tier] ?? plan.tier;
  const priceNote   = plan.price === 0 ? "원" : "원/월";
  const priceStr    = plan.price.toLocaleString("ko-KR");
  return {
    name:        displayName,
    price:       priceStr,
    priceNote,
    description: plan.recommended_for,
    isFeatured:  plan.tier === "PRO",
    ctaLabel:    CTA_LABEL[plan.tier] ?? "시작하기",
    features:    plan.features.map((label) => ({ label, included: true })),
  };
}

export function PricingClient() {
  const router = useRouter();
  const { user } = useAuthStore();
  const { data: plans, isLoading, isError } = usePricingPlans();

  const currentPlanName = user ? (TIER_MAP[user.tier] ?? null) : null;

  const handleCta = (planName: string) => {
    if (!user) { router.push("/signup"); return; }
    if (planName === "Free") {
      router.push("/dashboard");
    } else {
      router.push("/checkout");
    }
  };

  if (isLoading) {
    return (
      <section className="px-6 py-10 md:px-20" aria-label="요금제 플랜 로딩 중" aria-busy="true">
        <ul className="mx-auto grid max-w-5xl gap-5 md:grid-cols-3" role="list">
          {[0, 1, 2].map((i) => (
            <li key={i} className="h-96 animate-pulse rounded-2xl bg-muted" aria-hidden />
          ))}
        </ul>
      </section>
    );
  }

  if (isError) {
    return (
      <section className="px-6 py-10 text-center md:px-20">
        <p className="text-sm text-muted-foreground">요금제 정보를 불러올 수 없습니다. 잠시 후 다시 시도해주세요.</p>
      </section>
    );
  }

  if (!plans || plans.length === 0) return null;

  return (
    <section className="px-6 py-10 md:px-20" aria-label="요금제 플랜">
      <ul className="mx-auto grid max-w-5xl gap-5 md:grid-cols-3" role="list">
        {plans.map((plan) => {
          const cardProps = planToCardProps(plan);
          return (
            <li key={plan.tier}>
              <PlanCard
                {...cardProps}
                isCurrent={currentPlanName === cardProps.name}
                onCta={() => handleCta(cardProps.name)}
              />
            </li>
          );
        })}
      </ul>
    </section>
  );
}
