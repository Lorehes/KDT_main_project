// [목적] 가입 4단계 스테퍼 표시 컴포넌트 — 현재 단계·완료 단계를 시각적으로 표시
// [이유] D6~D9(STEP 1/4~4/4) 모든 온보딩 화면에서 공유하는 진행 상태 표시
// [사이드 임팩트] 없음 (presentational only)
// [수정 시 고려사항] steps 배열 변경 시 D6~D9 각 페이지의 currentStep prop도 함께 수정

import { cn } from "@/lib/utils";

const STEPS = ["이메일 인증", "약관 동의", "휴대폰 인증", "프로필"];

interface OnboardingStepperProps {
  currentStep: 1 | 2 | 3 | 4;
}

export function OnboardingStepper({ currentStep }: OnboardingStepperProps) {
  return (
    <div className="flex flex-col gap-1.5">
      <div className="flex gap-1.5" role="progressbar" aria-valuenow={currentStep} aria-valuemin={1} aria-valuemax={4} aria-label={`가입 진행 ${currentStep}/4단계`}>
        {STEPS.map((_, i) => (
          <span
            key={i}
            className={cn(
              "h-1.5 flex-1 rounded-full transition-colors",
              i < currentStep - 1 ? "bg-[color:var(--color-brand-sky)]" : i === currentStep - 1 ? "bg-primary" : "bg-border",
            )}
            aria-hidden
          />
        ))}
      </div>
      <p className="text-xs font-bold text-muted-foreground">
        STEP {currentStep} / 4 · {STEPS[currentStep - 1]}
      </p>
    </div>
  );
}
