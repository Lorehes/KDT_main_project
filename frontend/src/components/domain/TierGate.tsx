"use client";

// [목적] 티어 미달 컨텐츠 잠금 오버레이 — 업셀 CTA로 전환 유도
// [이유] 상위 Stage(Pro/Premium) 필드는 API에서 제외되어 반환됨. 마스킹이 아닌 잠금+업셀 패턴(design_structure §3.1)
// [사이드 임팩트] 공시 상세 Pro·Premium 섹션에서 사용. 클릭 시 ProUpsellModal 또는 /pricing 이동
// [수정 시 고려사항] children에 흐린 미리보기를 넣으면 overlay가 덮음.
//   API가 상위 Stage 데이터를 반환하지 않으므로 children은 플레이스홀더 skeleton만 사용

import { Lock } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useUIStore } from "@/lib/stores/uiStore";

interface TierGateProps {
  requiredTier: "PRO" | "PREMIUM";
  children?: React.ReactNode;
  className?: string;
}

const TIER_LABEL: Record<"PRO" | "PREMIUM", string> = {
  PRO: "Pro",
  PREMIUM: "Premium",
};

export function TierGate({ requiredTier, children, className }: TierGateProps) {
  const { setUpsellModalOpen } = useUIStore();

  return (
    <div className={`relative overflow-hidden rounded-[var(--radius-lg)] border border-border ${className ?? ""}`}>
      {children && (
        <div className="pointer-events-none select-none opacity-30 blur-[2px]">
          {children}
        </div>
      )}
      <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-background/90 p-6 text-center">
        <div className="grid size-12 place-items-center rounded-xl bg-[color:var(--color-brand-navy)]">
          <Lock className="size-5 text-white" aria-hidden />
        </div>
        <h4 className="text-base font-extrabold text-foreground">
          {TIER_LABEL[requiredTier]} 전용 분석
        </h4>
        <p className="max-w-[30ch] text-sm text-muted-foreground">
          과거 패턴·재무·업황 심층 분석은 {TIER_LABEL[requiredTier]} 플랜에서 제공됩니다.
        </p>
        <Button
          size="sm"
          onClick={() => setUpsellModalOpen(true)}
          aria-label={`${TIER_LABEL[requiredTier]} 플랜으로 업그레이드`}
        >
          {TIER_LABEL[requiredTier]} 시작하기 →
        </Button>
      </div>
    </div>
  );
}
