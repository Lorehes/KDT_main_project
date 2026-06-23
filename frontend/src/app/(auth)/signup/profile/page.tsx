"use client";

// [목적] 프로필 입력 화면(D9/m13, STEP 4/4) — 투자 경험·주 사용 시점 입력. 모두 선택 사항
// [이유] 개인화된 해석 톤·추천을 위한 데이터 수집. 미입력 시 기본값(INTERMEDIATE·REALTIME) 적용.
//   V22: BE UpdateMeRequest에 두 필드 추가됨 → useUpdateMe()로 PATCH /users/me 호출.
//   nickname 미전송: profile 단계에서 새로고침 시 authStore user=null 가능 → nickname 생략 안전(BE null 허용).
// [사이드 임팩트] useUpdateMe() onSuccess → fetchMe() → authStore.user 갱신.
//   선택 안 한 필드는 undefined로 전송 생략 — BE에서 null로 수신해 스킵.
// [수정 시 고려사항] 투자 경험은 공시 해석의 복잡도를 조정할 예정. 언제든 마이페이지에서 변경 가능.
//   투자 경험 값을 투자 권유 판단에 활용하는 UI 추가 금지 (통합기획서 §11.1).

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useUpdateMe } from "@/lib/api/auth";
import { toast } from "sonner";

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
  const updateMe = useUpdateMe();
  const [experience, setExperience] = useState("");
  const [time, setTime] = useState("");

  const handleSubmit = async () => {
    try {
      await updateMe.mutateAsync({
        // 빈 문자열(미선택) → undefined → JSON에서 생략 → BE null 수신 → 스킵
        investment_experience: (experience || undefined) as "BEGINNER" | "INTERMEDIATE" | "ADVANCED" | undefined,
        preferred_time:        (time        || undefined) as "REALTIME" | "LUNCH" | "EVENING" | undefined,
      });
      router.push("/signup/complete");
    } catch {
      toast.error("저장에 실패했습니다. 잠시 후 다시 시도해주세요.");
    }
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
                onClick={() => setExperience((prev) => prev === value ? "" : value)}
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
                onClick={() => setTime((prev) => prev === value ? "" : value)}
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

        <Button onClick={handleSubmit} disabled={updateMe.isPending} className="w-full">
          {updateMe.isPending ? "저장 중…" : "시작하기 →"}
        </Button>
      </div>
    </AuthLayout>
  );
}
