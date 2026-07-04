"use client";

// [목적] 이메일 가입 전용 약관 동의 화면(D7/m11, STEP 2/4) — 필수 4종 + 선택 1종 동의 수집.
// [이유] 기존 파일은 이메일·소셜 플로우를 ?oauth=true 분기로 혼합했으나(M-M1 이슈),
//   소셜 플로우를 /signup/terms/oauth/page.tsx로 분리해 이 파일은 이메일 전용으로 단순화됨.
//   E4 보안 이슈 해소: middleware가 onboarding_completed JWT claim으로 소셜 미동의자를 /signup/terms/oauth로 강제 리다이렉트하므로
//   이 페이지에서는 ?oauth=true URL 파라미터를 신뢰할 필요가 없음(M-S1 이슈 해소).
// [사이드 임팩트] TermsCheckboxList 공유 컴포넌트 사용 — TERMS_ITEMS 변경 시 양쪽 플로우 모두 반영.
//   signupStore.setConsents() → POST /auth/signup 순서 유지 — 이메일 모드 플로우 변경 없음.
// [수정 시 고려사항] 이 파일은 이메일 가입 전용. 소셜 가입 약관은 /signup/terms/oauth/page.tsx.
//   useSearchParams 제거로 Suspense 래퍼 불필요 — 정적 최적화 개선.

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { Button } from "@/components/ui/button";
import { useSignupStore } from "@/lib/stores/signupStore";
import { useSignup } from "@/lib/api/auth";
import { ApiException } from "@/lib/api/client";
import { TermsCheckboxList, TERMS_ITEMS } from "@/components/signup/TermsCheckboxList";

export default function TermsPage() {
  const router = useRouter();
  const { email, password, nickname, setConsents } = useSignupStore();
  const { mutateAsync: signupMutate, isPending } = useSignup();

  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [error, setError] = useState("");

  // 이메일 모드: store에 email 없으면 가입 첫 단계로 복귀
  useEffect(() => {
    if (!email) router.replace("/signup");
  }, [email, router]);

  const requiredDone = TERMS_ITEMS.filter((t) => t.required).every((t) => checked[t.key]);

  const handleContinue = async () => {
    if (!requiredDone) return;
    setConsents(checked);

    try {
      const resolvedNickname = (nickname || "").trim() || email?.split("@")[0] || "사용자";
      await signupMutate({
        email,
        password,
        nickname: resolvedNickname,
        termsAgreed:      !!checked["TERMS"],
        privacyAgreed:    !!checked["PRIVACY"],
        disclaimerAgreed: !!checked["DISCLAIMER"],
        marketingAgreed:  !!checked["MARKETING"],
      });
      router.push("/signup/phone");
    } catch (e) {
      const msg = e instanceof ApiException ? e.body.message : "가입에 실패했습니다. 다시 시도해주세요.";
      setError(msg);
    }
  };

  return (
    <AuthLayout
      heading={<>서비스<br /><span className="text-[color:var(--color-brand-sky)]">이용 약관</span></>}
      subtext="안전한 이용을 위해 필수 약관에 동의해주세요. 관심 종목·매수가는 암호화되어 보호됩니다."
    >
      <div className="flex flex-col gap-7">
        <OnboardingStepper currentStep={2} />
        <h2 className="text-2xl font-extrabold tracking-tight text-foreground">약관 동의</h2>

        {error && (
          <p className="rounded-lg bg-destructive/10 px-4 py-2.5 text-sm text-destructive" role="alert">
            {error}
          </p>
        )}

        <TermsCheckboxList checked={checked} onChange={setChecked} />

        <Button onClick={handleContinue} disabled={!requiredDone || isPending} className="w-full">
          {isPending ? "계정 생성 중..." : "동의하고 계속하기"}
        </Button>
      </div>
    </AuthLayout>
  );
}
