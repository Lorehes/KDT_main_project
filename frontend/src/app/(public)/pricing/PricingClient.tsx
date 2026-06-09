"use client";

// [목적] 요금제 플랜 카드 그리드 — 현재 사용자 등급 바인딩으로 CTA 비활성화
// [이유] authStore(Zustand)는 클라이언트 전용이므로 page.tsx(서버 컴포넌트)와 분리
// [사이드 임팩트] authStore.user.tier에 따라 현재 플랜 CTA를 "현재 플랜"(disabled)으로 표시
// [수정 시 고려사항] 플랜 데이터는 현재 정적. /pricing/plans API 연동 시 useQuery로 교체.
//   결제 연동(W7) 전까지 Pro/Premium CTA는 /checkout으로 이동(mockup)

import { useRouter } from "next/navigation";
import { PlanCard, type PlanCardProps } from "@/components/domain/PlanCard";
import { useAuthStore } from "@/lib/stores/authStore";

const PLANS: Omit<PlanCardProps, "isCurrent" | "onCta">[] = [
  {
    name: "Free",
    price: "0",
    priceNote: "원",
    description: "공시 알림 입문",
    isFeatured: false,
    ctaLabel: "무료로 시작하기",
    features: [
      { label: "보유 종목 3개", included: true },
      { label: "호재/중립/악재 분류 + 한 줄 요약", included: true },
      { label: "일 5건 카카오 알림톡", included: true },
      { label: "과거 패턴 분석", included: false },
      { label: "재무·업황 분석", included: false },
    ],
  },
  {
    name: "Pro",
    price: "9,900",
    priceNote: "원/월",
    description: "핵심 수익원 · 적극 투자자용",
    isFeatured: true,
    ctaLabel: "7일 무료 체험 →",
    features: [
      { label: "무제한 종목 등록", included: true },
      { label: "상세 해석(영향 이유·예상 방향)", included: true },
      { label: "과거 유사 공시 + 주가 반응 차트", included: true },
      { label: "무제한 알림 + 중요도 필터", included: true },
      { label: "공시 히스토리 3개월", included: true },
    ],
  },
  {
    name: "Premium",
    price: "29,900",
    priceNote: "원/월",
    description: "전문 투자자 · 풀 분석",
    isFeatured: false,
    ctaLabel: "Premium 시작",
    features: [
      { label: "Pro 전체 기능", included: true },
      { label: "섹터·경쟁사 공시 분석", included: true },
      { label: "포트폴리오 리스크 스코어", included: true },
      { label: "텔레그램 봇 / 웹훅 API", included: true },
      { label: 'AI Q&A — "이 공시 어떻게 대응?"', included: true },
    ],
  },
];

const TIER_MAP: Record<string, "Free" | "Pro" | "Premium"> = {
  FREE: "Free",
  PRO: "Pro",
  PREMIUM: "Premium",
};

export function PricingClient() {
  const router = useRouter();
  const { user } = useAuthStore();
  const currentPlanName = user ? TIER_MAP[user.tier] : null;

  const handleCta = (planName: string) => {
    if (!user) {
      router.push("/signup");
      return;
    }
    if (planName === "Free") {
      router.push("/dashboard");
    } else {
      router.push("/checkout");
    }
  };

  return (
    <section className="px-6 py-10 md:px-20" aria-label="요금제 플랜">
      <ul className="mx-auto grid max-w-5xl gap-5 md:grid-cols-3" role="list">
        {PLANS.map((plan) => (
          <li key={plan.name}>
            <PlanCard
              {...plan}
              isCurrent={currentPlanName === plan.name}
              onCta={() => handleCta(plan.name)}
            />
          </li>
        ))}
      </ul>
    </section>
  );
}
