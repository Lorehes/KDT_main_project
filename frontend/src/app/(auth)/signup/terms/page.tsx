"use client";

// [목적] 약관 동의 화면(D7/m11, STEP 2/4) — 필수 4종 + 선택 1종 동의 수집 + 자본시장법 고지.
//   이메일 가입(기본 모드)과 소셜 가입(?oauth=true 모드) 양쪽을 처리.
// [이유] DISCLAIMER(정보 제공 도구 동의)는 자본시장법 제6조·제17조 리스크 방어를 위한 필수 동의.
//   소셜 가입 시 BE autoSignup이 계정만 생성(동의 보류) → 이 화면에서 동의 수집 후 POST /users/me/oauth-consent 호출.
// [사이드 임팩트] 이메일 모드: 동의 수집 후 signupStore에 저장 → POST /auth/signup 호출 → /signup/phone.
//   소셜 모드(?oauth=true): signupStore 의존 없음 → POST /users/me/oauth-consent 호출 → /signup/phone.
//   email 미존재(이메일 모드에서 새로고침 등) 시 useEffect redirect → /signup 첫 단계로 복귀(R5).
//   default export가 Suspense 래퍼(TermsPageWrapper) — useSearchParams() 사용으로 인한 Next.js 15
//   빌드 경고 억제 및 정적 최적화 유지. 실제 로직은 TermsPage 내부 컴포넌트에 위치.
// [수정 시 고려사항] 필수 동의 미완료 시 버튼 비활성화. 동의 시각은 BE created_at으로 로깅됨.
//   소셜 모드에서 oauth-consent 실패 시 에러 메시지 표시 후 재시도 — 이탈 시 계정은 남고 동의 없는 상태.
//   재접속 시 oauthCallback이 hasRequiredConsents() 확인 후 다시 is_new_user=true 반환 → 이 화면으로 재유도.

import { Suspense, useState, useEffect } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useSignupStore } from "@/lib/stores/signupStore";
import { useSignup, useOAuthConsent } from "@/lib/api/auth";
import { ApiException } from "@/lib/api/client";

const TERMS_ITEMS: { key: string; label: string; required: boolean; link?: string; apiKey?: keyof { termsAgreed: boolean; privacyAgreed: boolean; disclaimerAgreed: boolean; marketingAgreed: boolean } }[] = [
  { key: "TERMS",      label: "서비스 이용약관",                               required: true,  link: "#", apiKey: "termsAgreed" },
  { key: "PRIVACY",    label: "개인정보 수집·이용 동의",                         required: true,  link: "#", apiKey: "privacyAgreed" },
  { key: "DISCLAIMER", label: "본 서비스는 투자 자문이 아닌 정보 제공 도구임에 동의\n(자본시장법 제6조·제17조)", required: true, link: "#", apiKey: "disclaimerAgreed" },
  { key: "AGE",        label: "만 14세 이상입니다", required: true },  // UI 전용 — API 미전송
  { key: "MARKETING",  label: "마케팅 정보 수신 (혜택·이벤트)", required: false, apiKey: "marketingAgreed" },
];

function TermsPage() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const isOAuth = searchParams.get("oauth") === "true";

  const { email, password, nickname, setConsents } = useSignupStore();
  const { mutateAsync: signupMutate, isPending: signupPending } = useSignup();
  const { mutateAsync: oauthConsentMutate, isPending: oauthPending } = useOAuthConsent();
  const isPending = signupPending || oauthPending;

  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [error, setError] = useState("");

  // R5: 이메일 모드에서만 store 체크 — 소셜 모드(?oauth=true)는 signupStore 불필요
  useEffect(() => {
    if (!isOAuth && !email) router.replace("/signup");
  }, [isOAuth, email, router]);

  const toggle = (key: string) => setChecked((p) => ({ ...p, [key]: !p[key] }));
  const toggleAll = () => {
    const requiredAllChecked = TERMS_ITEMS.filter((t) => t.required).every((t) => checked[t.key]);
    const next: Record<string, boolean> = { ...checked };
    TERMS_ITEMS.filter((t) => t.required).forEach((t) => { next[t.key] = !requiredAllChecked; });
    setChecked(next);
  };

  const requiredDone = TERMS_ITEMS.filter((t) => t.required).every((t) => checked[t.key]);
  // WCAG 4.1.2: aria-checked="mixed" — 일부만 체크된 인디터미네이트 상태를 스크린리더에 전달
  const someRequired = TERMS_ITEMS.filter((t) => t.required).some((t) => checked[t.key]);
  const partialRequired = someRequired && !requiredDone;

  const handleContinue = async () => {
    if (!requiredDone) return;
    // 이메일 모드에서만 signupStore에 동의 상태 저장 — 소셜 모드는 직접 API 호출로 처리
    if (!isOAuth) setConsents(checked);

    try {
      if (isOAuth) {
        // 소셜 가입 모드: 이미 계정이 있고 토큰 쿠키도 있음 → 동의만 저장
        await oauthConsentMutate({
          termsAgreed:      !!checked["TERMS"],
          privacyAgreed:    !!checked["PRIVACY"],
          disclaimerAgreed: !!checked["DISCLAIMER"],
          marketingAgreed:  !!checked["MARKETING"],
        });
      } else {
        // 이메일 가입 모드: 동의 저장 + 계정 생성 동시 처리
        // R5: email?.split — store 클리어 직후 redirect 전 짧은 순간 email이 없을 수 있어 optional chaining
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
      }
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

        {error && <p className="rounded-lg bg-destructive/10 px-4 py-2.5 text-sm text-destructive" role="alert">{error}</p>}

        <div className="rounded-2xl border border-border bg-card p-1">
          {/* 전체 동의 */}
          <button
            type="button"
            onClick={toggleAll}
            className={cn(
              "flex w-full items-center gap-3 rounded-xl border-[1.5px] p-4 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              requiredDone ? "border-primary bg-primary/5" : "border-border",
            )}
            role="checkbox"
            aria-checked={requiredDone ? true : partialRequired ? "mixed" : false}
          >
            <Checkbox checked={requiredDone} />
            <span className="text-base font-extrabold text-foreground">필수 항목 전체 동의</span>
          </button>

          <div className="mt-1 flex flex-col divide-y divide-border px-2">
            {TERMS_ITEMS.map(({ key, label, required, link }) => (
              <div key={key} className="flex items-start gap-3 py-3.5">
                <button
                  type="button"
                  onClick={() => toggle(key)}
                  role="checkbox"
                  aria-checked={!!checked[key]}
                  aria-label={label}
                  className="mt-0.5 shrink-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-1"
                >
                  <Checkbox checked={!!checked[key]} />
                </button>
                <p className="flex-1 text-sm leading-relaxed text-foreground">
                  <span className={cn("mr-1.5 inline-block rounded-md px-1.5 py-0.5 text-[10px] font-extrabold", required ? "bg-destructive/10 text-destructive" : "bg-muted text-muted-foreground")}>
                    {required ? "[필수]" : "[선택]"}
                  </span>
                  {label}
                </p>
                {link && (
                  <a href={link} className="shrink-0 text-xs text-muted-foreground underline hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring" aria-label={`${label} 약관 보기`}>
                    보기
                  </a>
                )}
              </div>
            ))}
          </div>
        </div>

        <Button onClick={handleContinue} disabled={!requiredDone || isPending} className="w-full">
          {isPending ? "계정 생성 중..." : "동의하고 계속하기"}
        </Button>
      </div>
    </AuthLayout>
  );
}

/** useSearchParams() 사용으로 인해 Suspense 경계 필수 — Next.js 15 정적 최적화 유지 */
export default function TermsPageWrapper() {
  return (
    <Suspense>
      <TermsPage />
    </Suspense>
  );
}

function Checkbox({ checked }: { checked: boolean }) {
  return (
    <span className={cn("grid size-6 place-items-center rounded-[7px] border-2 transition-colors", checked ? "border-primary bg-primary" : "border-border bg-background")} aria-hidden>
      {checked && (
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M5 12.5 10 17.5 19 7" />
        </svg>
      )}
    </span>
  );
}
