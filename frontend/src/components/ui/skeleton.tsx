// [목적] 로딩 Skeleton UI — animate-pulse 플레이스홀더로 레이아웃 붕괴 없이 로딩 상태 표현
// [이유] "불러오는 중..." 텍스트는 레이아웃 시프트 유발 + 시각적 피드백 미흡. shadcn/ui Skeleton 패턴 채택
// [사이드 임팩트] portfolios·disclosures·notifications·dashboard 페이지의 isLoading 블록 교체
// [수정 시 고려사항] className으로 h-/w- 크기 제어. 라운드는 rounded-md 기본 (필요 시 rounded-full/rounded-lg 오버라이드).
//   motion-reduce:animate-none — prefers-reduced-motion:reduce 시 맥박 애니메이션 중단 (WCAG 2.3.3 / 전정기관 장애 배려)

import { cn } from "@/lib/utils";

function Skeleton({ className, ...props }: React.HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("animate-pulse rounded-md bg-muted motion-reduce:animate-none motion-reduce:bg-muted/50", className)}
      {...props}
    />
  );
}

export { Skeleton };
