// [목적] 호재·악재·중립·판단보류 배지 — 색+아이콘+텍스트 3중 표기
// [이유] WCAG 2.1 AA 준수 + 색맹 배려. 색상만으로 의미 전달 금지(CLAUDE.md §6-5, design_structure §2.2)
// [사이드 임팩트] DisclosureCard·공시 상세 등 모든 감성 표기 화면에서 사용. 토큰 변경 시 전체 반영
// [수정 시 고려사항] 한국 증시 관행: 호재(상승)=빨강, 악재(하락)=파랑. 서구 관행 반대.
//   판단보류(is_withheld)는 보라색(#5B43C0, --sentiment-withheld) 별도 토큰 — 중립(회색)과 구분 (design_structure §2.2)

import { TrendingUp, TrendingDown, Minus, AlertCircle } from "lucide-react";
import { cn } from "@/lib/utils";
import type { Sentiment } from "@/lib/api/disclosures";

type SentimentVariant = Sentiment | "WITHHELD";

const CONFIG: Record<
  SentimentVariant,
  { label: string; Icon: React.ElementType; className: string; ariaLabel: string }
> = {
  POSITIVE: {
    label: "호재",
    Icon: TrendingUp,
    className:
      "bg-[color:var(--color-sentiment-positive)] text-[color:var(--color-sentiment-positive-foreground)]",
    ariaLabel: "호재 — 주가 상승 가능성",
  },
  NEGATIVE: {
    label: "악재",
    Icon: TrendingDown,
    className:
      "bg-[color:var(--color-sentiment-negative)] text-[color:var(--color-sentiment-negative-foreground)]",
    ariaLabel: "악재 — 주가 하락 가능성",
  },
  NEUTRAL: {
    label: "중립",
    Icon: Minus,
    className:
      "bg-[color:var(--color-sentiment-neutral)] text-[color:var(--color-sentiment-neutral-foreground)]",
    ariaLabel: "중립 — 주가 영향 미미",
  },
  WITHHELD: {
    label: "판단 보류",
    Icon: AlertCircle,
    className:
      "bg-[color:var(--color-sentiment-withheld)] text-[color:var(--color-sentiment-withheld-foreground)]",
    ariaLabel: "판단 보류 — AI 신뢰도 낮음",
  },
};

interface SentimentBadgeProps {
  sentiment: Sentiment;
  isWithheld?: boolean;
  size?: "sm" | "md" | "lg";
  className?: string;
}

// lg는 필터 칩 등 "전체" 버튼(px-4 py-2 text-sm)과 동일 크기를 맞추기 위한 사이즈
const SIZE_CLASS: Record<NonNullable<SentimentBadgeProps["size"]>, string> = {
  sm: "px-2 py-1 text-[11px]",
  md: "px-2.5 py-1.5 text-xs",
  lg: "px-4 py-2 text-sm",
};
const ICON_SIZE: Record<NonNullable<SentimentBadgeProps["size"]>, string> = {
  sm: "size-3",
  md: "size-3.5",
  lg: "size-4",
};

export function SentimentBadge({
  sentiment,
  isWithheld = false,
  size = "md",
  className,
}: SentimentBadgeProps) {
  const variant: SentimentVariant = isWithheld ? "WITHHELD" : sentiment;
  const { label, Icon, className: colorClass, ariaLabel } = CONFIG[variant];

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full font-extrabold tracking-tight",
        SIZE_CLASS[size],
        // --radius-badge 토큰 정의 후 사용. 미정의 시 rounded-full 폴백

        colorClass,
        className,
      )}
      role="img"
      aria-label={ariaLabel}
    >
      <Icon className={ICON_SIZE[size]} aria-hidden />
      {label}
    </span>
  );
}
