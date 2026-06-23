"use client";

// [목적] 소셜(OAuth) 가입 전용 약관 동의 화면 — BE autoSignup()이 계정만 생성한 상태에서 동의를 수집.
//   middleware가 onboarding_completed=false 사용자를 이 경로로 강제 리다이렉트(E4 보안 이슈 해소).
// [이유] 기존 /signup/terms?oauth=true URL 파라미터 의존(M-S1 이슈)을 제거하고
//   독립 경로로 분리해 URL 조작으로 소셜 동의 API를 호출할 수 없도록 방어.
//   소셜 가입 시 이미 dr_session 쿠키가 존재하므로 별도 인증 없이 /users/me/oauth-consent 호출 가능.
// [사이드 임팩트] 이 페이지 성공 후 /signup/phone으로 이동 — 기존 소셜 온보딩 플로우 유지.
//   oauth-consent API가 멱등이므로 이미 동의한 사용자가 재접근해도 안전.
//   onboarding_completed_at은 /signup/complete에서 설정 — 이 페이지 완료 후에도 아직 false.
// [수정 시 고려사항] 이 페이지는 소셜 전용. 이메일 가입 약관은 /signup/terms/page.tsx.
//   signupStore 의존 없음 — 소셜 가입은 BE autoSignup()이 계정을 생성한 후 동의만 여기서 저장.
//   middleware 리다이렉트 루프 방지: /signup/* 경로는 onboarding_completed 체크 대상에서 제외됨.

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { Button } from "@/components/ui/button";
import { useOAuthConsent } from "@/lib/api/auth";
import { ApiException } from "@/lib/api/client";
import { TermsCheckboxList, TERMS_ITEMS } from "@/components/signup/TermsCheckboxList";

export default function OAuthTermsPage() {
  const router = useRouter();
  const { mutateAsync: oauthConsentMutate, isPending } = useOAuthConsent();

  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [error, setError] = useState("");

  const requiredDone = TERMS_ITEMS.filter((t) => t.required).every((t) => checked[t.key]);

  const handleContinue = async () => {
    if (!requiredDone) return;

    try {
      await oauthConsentMutate({
        termsAgreed:      !!checked["TERMS"],
        privacyAgreed:    !!checked["PRIVACY"],
        disclaimerAgreed: !!checked["DISCLAIMER"],
        marketingAgreed:  !!checked["MARKETING"],
      });
      router.push("/signup/phone");
    } catch (e) {
      // 401: 세션 없는 접근(쿠키 만료 등) → 재로그인 안내
      const isUnauth = e instanceof ApiException && e.body.status === 401;
      const msg = isUnauth
        ? "세션이 만료됐습니다. 다시 로그인해주세요."
        : e instanceof ApiException
          ? e.body.message
          : "동의 저장에 실패했습니다. 다시 시도해주세요.";
      setError(msg);
      if (isUnauth) router.replace("/login");
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
          {isPending ? "동의 저장 중..." : "동의하고 계속하기"}
        </Button>
      </div>
    </AuthLayout>
  );
}
