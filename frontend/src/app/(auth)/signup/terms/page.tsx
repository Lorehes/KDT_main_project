"use client";

// [목적] 약관 동의 화면(D7/m11, STEP 2/4) — 필수 4종 + 선택 1종 동의 수집 + 자본시장법 고지
// [이유] DISCLAIMER(정보 제공 도구 동의)는 자본시장법 제6조·제17조 리스크 방어를 위한 필수 동의
// [사이드 임팩트] 동의 수집 후 signupStore에 저장. POST /auth/signup API 호출은 terms 단계에서 수행
// [수정 시 고려사항] 필수 동의 미완료 시 버튼 비활성화. 동의 시각·버전은 API 응답의 created_at으로 로깅됨.
//   policy_version은 "v1.0" 하드코딩 — 정책 변경 시 버전 업 필요

import { useState } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { useSignupStore } from "@/lib/stores/signupStore";
import { useSignup } from "@/lib/api/auth";
import { ApiException } from "@/lib/api/client";
import type { ConsentType } from "@/lib/api/auth";

// AGE는 API ConsentType에 포함되지 않으므로 로컬 전용 키로 관리. API 전송 시 제외
const TERMS_ITEMS: { key: ConsentType | "AGE"; label: string; required: boolean; link?: string; apiExclude?: boolean }[] = [
  { key: "TERMS",      label: "서비스 이용약관",                               required: true,  link: "#" },
  { key: "PRIVACY",    label: "개인정보 수집·이용 동의",                         required: true,  link: "#" },
  { key: "DISCLAIMER", label: "본 서비스는 투자 자문이 아닌 정보 제공 도구임에 동의\n(자본시장법 제6조·제17조)", required: true, link: "#" },
  { key: "AGE",        label: "만 14세 이상입니다", required: true, apiExclude: true },
  { key: "MARKETING",  label: "마케팅 정보 수신 (혜택·이벤트)", required: false },
];

const POLICY_VERSION = "v1.0";

export default function TermsPage() {
  const router = useRouter();
  const { email, password, nickname, setConsents } = useSignupStore();
  const { mutateAsync, isPending } = useSignup();
  const [checked, setChecked] = useState<Record<string, boolean>>({});
  const [error, setError] = useState("");

  const toggle = (key: string) => setChecked((p) => ({ ...p, [key]: !p[key] }));
  const toggleAll = () => {
    const allChecked = TERMS_ITEMS.every((t) => checked[t.key]);
    const next: Record<string, boolean> = {};
    TERMS_ITEMS.forEach((t) => { next[t.key] = !allChecked; });
    setChecked(next);
  };

  const requiredDone = TERMS_ITEMS.filter((t) => t.required).every((t) => checked[t.key]);
  const allDone = TERMS_ITEMS.every((t) => checked[t.key]);

  const handleContinue = async () => {
    if (!requiredDone) return;
    setConsents(checked);

    const consents = TERMS_ITEMS
      .filter((t) => !t.apiExclude) // AGE는 API 미지원 — 제외
      .map((t) => ({
        consent_type: t.key as ConsentType,
        agreed: !!checked[t.key],
        policy_version: POLICY_VERSION,
      }));

    try {
      await mutateAsync({ email, password, nickname: nickname || undefined, consents });
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
              "flex w-full items-center gap-3 rounded-xl border-[1.5px] p-4 text-left transition-colors",
              allDone ? "border-primary bg-primary/5" : "border-border",
            )}
            aria-pressed={allDone}
          >
            <Checkbox checked={allDone} />
            <span className="text-base font-extrabold text-foreground">전체 동의합니다</span>
          </button>

          <div className="mt-1 flex flex-col divide-y divide-border px-2">
            {TERMS_ITEMS.map(({ key, label, required, link }) => (
              <div key={key} className="flex items-start gap-3 py-3.5">
                <button
                  type="button"
                  onClick={() => toggle(key)}
                  aria-pressed={!!checked[key]}
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
