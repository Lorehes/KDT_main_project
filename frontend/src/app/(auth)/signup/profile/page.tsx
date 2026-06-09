"use client";

// [목적] 프로필 입력 화면(D9/m13, STEP 4/4) — 투자 경험·주 사용 시점 입력. 모두 선택 사항
// [이유] 개인화된 해석 톤·추천을 위한 데이터 수집. 미입력 시 기본값(INTERMEDIATE·REALTIME) 적용
// [사이드 임팩트] BE UpdateMeRequest에 investment_experience·preferred_time 미지원 — API 호출 스킵, UX 수집 전용 화면.
//   BE에 해당 필드 추가 후 handleSubmit 내 apiClient 호출 복원 필요
// [수정 시 고려사항] 투자 경험은 공시 해석의 복잡도를 조정할 예정. 언제든 마이페이지에서 변경 가능

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";

const EXPERIENCE_OPTIONS = [
  { value: "BEGINNER",     label: "입문 (1년 미만)",  desc: "공시 용어부터 쉽게 풀어드려요" },
  { value: "INTERMEDIATE", label: "중급 (1~5년)",      desc: "핵심 요약 + 근거 중심" },
  { value: "ADVANCED",     label: "상급 (5년 이상)",   desc: "상세 데이터·과거 패턴 위주" },
];

const TIME_OPTIONS = [
  { value: "REALTIME", label: "실시간" },
  { value: "LUNCH",    label: "점심" },
  { value: "EVENING",  label: "퇴근 후" },
];

export default function ProfilePage() {
  const router = useRouter();
  const [experience, setExperience] = useState("INTERMEDIATE");
  const [time, setTime] = useState("REALTIME");
  const [isSubmitting, setIsSubmitting] = useState(false);

  const handleSubmit = async () => {
    setIsSubmitting(true);
    // BE UpdateMeRequest에 investment_experience·preferred_time 미지원(nickname 단일 필드).
    // 해당 BE 필드 추가 후 아래 apiClient 호출 복원. 현재는 UX 수집 전용으로 스킵.
    setIsSubmitting(false);
    router.push("/signup/complete");
  };

  return (
    <AuthLayout
      heading={<>거의<br /><span className="text-[color:var(--color-brand-sky)]">다 왔어요</span></>}
      subtext="맞춤 해석을 위해 알려주세요. 모두 선택 사항이며 언제든 바꿀 수 있어요."
    >
      <div className="flex flex-col gap-7">
        <OnboardingStepper currentStep={4} />
        <h2 className="text-2xl font-extrabold tracking-tight text-foreground">프로필 (선택)</h2>

        {/* 투자 경험 */}
        <fieldset>
          <legend className="mb-3 text-sm font-extrabold text-foreground">투자 경험</legend>
          <div className="flex flex-col gap-2">
            {EXPERIENCE_OPTIONS.map(({ value, label, desc }) => (
              <button
                key={value}
                type="button"
                onClick={() => setExperience(value)}
                aria-pressed={experience === value}
                className={cn(
                  "flex items-center gap-3 rounded-xl border-[1.5px] p-4 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  experience === value ? "border-primary bg-primary/5" : "border-border bg-background",
                )}
              >
                <span className={cn("grid size-5 place-items-center rounded-full border-2 transition-colors", experience === value ? "border-primary" : "border-border")} aria-hidden>
                  {experience === value && <span className="size-2.5 rounded-full bg-primary" />}
                </span>
                <span>
                  <span className="block text-sm font-bold text-foreground">{label}</span>
                  <span className="block text-xs text-muted-foreground">{desc}</span>
                </span>
              </button>
            ))}
          </div>
        </fieldset>

        {/* 주 사용 시점 */}
        <fieldset>
          <legend className="mb-3 text-sm font-extrabold text-foreground">주 사용 시점</legend>
          <div className="flex gap-2" role="group">
            {TIME_OPTIONS.map(({ value, label }) => (
              <button
                key={value}
                type="button"
                onClick={() => setTime(value)}
                aria-pressed={time === value}
                className={cn(
                  "flex-1 rounded-full border-[1.5px] py-2.5 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                  time === value ? "border-primary bg-primary text-primary-foreground" : "border-border bg-background text-muted-foreground",
                )}
              >
                {label}
              </button>
            ))}
          </div>
        </fieldset>

        <Button onClick={handleSubmit} disabled={isSubmitting} className="w-full">
          {isSubmitting ? "저장 중..." : "시작하기 →"}
        </Button>
      </div>
    </AuthLayout>
  );
}
