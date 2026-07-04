// [목적] 요금제 페이지(D4/m07) — Free·Pro·Premium 플랜 비교 + CTA + B2B 섹션
// [이유] 비로그인·로그인 모두 접근 가능한 퍼블릭 페이지. 현재 등급 바인딩으로 CTA를 비활성화
// [사이드 임팩트] PlanCard 컴포넌트와 authStore 의존. 미래에 /pricing/plans API 응답으로 플랜 데이터 교체 예정
// [수정 시 고려사항] MVP는 정적 플랜 데이터. API 연동 시 Suspense + TanStack Query로 교체.
//   카카오페이/결제 연동 전까지 CTA는 /checkout(mockup)으로 이동.
//   투자 권유 표현 금지 — 플랜 혜택 문구에 "수익 보장" 등 사용 불가

import type { Metadata } from "next";
import Link from "next/link";
import { PricingClient } from "./PricingClient";
import { DisclaimerNotice } from "@/components/domain/DisclaimerNotice";

export const metadata: Metadata = {
  title: "요금제 — 공시레이더",
  description:
    "Free·Pro·Premium 세 가지 플랜으로 공시 분석의 깊이를 선택하세요. 7일 무료 체험 가능.",
};

export default function PricingPage() {
  return (
    <main>
      {/* 헤더 */}
      <section className="border-b border-border bg-background px-6 py-14 text-center md:px-20">
        <p className="text-xs font-extrabold uppercase tracking-widest text-primary">
          Pricing
        </p>
        <h1 className="mt-3 text-[32px] font-extrabold tracking-tight text-foreground">
          필요한 만큼만, 명확하게
        </h1>
        <p className="mt-3 text-base text-muted-foreground">
          알림은 무료. 깊은 분석이 필요할 때 업그레이드하세요.
        </p>
      </section>

      {/* 플랜 카드 그리드 — 현재 등급 바인딩은 클라이언트 컴포넌트 */}
      <PricingClient />

      {/* B2B 섹션 */}
      <section className="px-6 py-10 md:px-20">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-6 rounded-2xl bg-[color:var(--color-brand-navy)] px-6 py-5 md:px-8">
          <div>
            <p className="text-[11px] font-extrabold uppercase tracking-widest text-[color:var(--color-brand-sky)]">
              B2B
            </p>
            <p className="mt-1 text-base font-extrabold text-white">
              증권사·핀테크용 화이트라벨 API
            </p>
          </div>
          <Link
            href="mailto:business@dartcommons.kr"
            className="shrink-0 rounded-xl bg-white px-5 py-2.5 text-sm font-bold text-[color:var(--color-brand-navy)] transition-colors hover:bg-white/90"
          >
            영업팀 문의 →
          </Link>
        </div>
      </section>

      {/* 면책 고지 */}
      <div className="border-t border-border px-6 pb-12 pt-6 md:px-20">
        <div className="mx-auto max-w-5xl">
          <DisclaimerNotice className="border-0 bg-transparent px-0 text-[11px]" />
        </div>
      </div>
    </main>
  );
}
