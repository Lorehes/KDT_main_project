// [목적] 요금제 비교 카드 — Free/Pro/Premium 기능 목록·CTA·현재 등급 바인딩
// [이유] 요금제 페이지와 업셀 모달에서 재사용
// [사이드 임팩트] 현재 사용자 등급(user.tier)에 따라 CTA 비활성화
// [수정 시 고려사항] 가격·기능 목록은 /pricing/plans API 응답으로 교체 예정(W2에서). 현재 정적

import { Check, Minus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

export interface PlanFeature {
  label: string;
  included: boolean;
}

export interface PlanCardProps {
  name: string;
  price: string;
  priceNote?: string;
  description: string;
  features: PlanFeature[];
  ctaLabel: string;
  isFeatured?: boolean;
  isCurrent?: boolean;
  onCta?: () => void;
}

export function PlanCard({
  name,
  price,
  priceNote,
  description,
  features,
  ctaLabel,
  isFeatured,
  isCurrent,
  onCta,
}: PlanCardProps) {
  return (
    <article
      className={cn(
        "relative flex flex-col gap-5 rounded-2xl border p-7",
        isFeatured ? "border-[2px] border-primary shadow-md" : "border-border",
      )}
      aria-label={`${name} 요금제`}
    >
      {isFeatured && (
        <span className="absolute -top-3 left-7 rounded-lg bg-primary px-3 py-1.5 text-[11px] font-extrabold text-primary-foreground">
          가장 인기
        </span>
      )}

      <div>
        <h3 className="text-xl font-extrabold text-foreground">{name}</h3>
        <div className="mt-2.5 flex items-baseline gap-1">
          <span className="font-mono text-4xl font-extrabold text-foreground">{price}</span>
          {priceNote && <span className="text-sm text-muted-foreground">{priceNote}</span>}
        </div>
        <p className="mt-1 text-sm text-muted-foreground">{description}</p>
      </div>

      <ul className="flex flex-col gap-3" aria-label={`${name} 기능 목록`}>
        {features.map(({ label, included }) => (
          <li key={label} className={cn("flex items-start gap-2.5 text-sm", !included && "opacity-40")}>
            {included ? (
              <Check className="mt-0.5 size-4 shrink-0 text-[color:var(--color-sentiment-positive)]" aria-hidden />
            ) : (
              <Minus className="mt-0.5 size-4 shrink-0 text-muted-foreground" aria-hidden />
            )}
            {label}
          </li>
        ))}
      </ul>

      <Button
        variant={isFeatured ? "default" : "outline"}
        className="mt-auto w-full"
        disabled={isCurrent}
        onClick={onCta}
        aria-label={isCurrent ? `현재 ${name} 플랜` : ctaLabel}
      >
        {isCurrent ? "현재 플랜" : ctaLabel}
      </Button>
    </article>
  );
}
