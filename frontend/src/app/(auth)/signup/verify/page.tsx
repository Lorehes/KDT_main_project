"use client";

// [목적] 이메일 인증 화면(D6/m10, STEP 1/4) — 6자리 OTP 입력·타이머·재전송
// [이유] 이메일 소유 확인 후 다음 단계(약관 동의)로 이동
// [사이드 임팩트] signupStore.email이 비어 있으면 /signup으로 복귀 (직접 접근 방어)
// [수정 시 고려사항] 백엔드 POST /auth/email/verify 엔드포인트 확인 필요(api_spec 미명시).
//   현재 타이머 만료 후 재전송 UI만 제공, 실제 API 연동은 백엔드 준비 후 추가.

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { OTPInput } from "@/components/domain/OTPInput";
import { Button } from "@/components/ui/button";
import { useSignupStore } from "@/lib/stores/signupStore";

const TIMER_SECONDS = 5 * 60;

export default function VerifyPage() {
  const router = useRouter();
  const { email } = useSignupStore();
  const [code, setCode] = useState("");
  const [seconds, setSeconds] = useState(TIMER_SECONDS);
  const [error, setError] = useState("");

  useEffect(() => {
    if (!email) { router.replace("/signup"); return; }
  }, [email, router]);

  useEffect(() => {
    if (seconds <= 0) return;
    const t = setTimeout(() => setSeconds((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [seconds]);

  const mm = String(Math.floor(seconds / 60)).padStart(2, "0");
  const ss = String(seconds % 60).padStart(2, "0");

  const handleSubmit = () => {
    if (code.length < 6) { setError("6자리 인증번호를 모두 입력해주세요."); return; }
    // TODO: POST /auth/email/verify — 백엔드 엔드포인트 확인 후 연동
    router.push("/signup/terms");
  };

  const handleResend = () => {
    setSeconds(TIMER_SECONDS);
    setCode("");
    setError("");
    // TODO: 재전송 API 호출
  };

  return (
    <AuthLayout
      heading={<>메일함을<br /><span className="text-[color:var(--color-brand-sky)]">확인하세요</span></>}
      subtext={`${email || "입력하신 이메일"}로 보낸 6자리 인증번호를 입력해주세요.`}
    >
      <div className="flex flex-col gap-7">
        <OnboardingStepper currentStep={1} />
        <div>
          <h2 className="text-2xl font-extrabold tracking-tight text-foreground">인증번호 입력</h2>
          <p className="mt-1.5 text-sm text-muted-foreground">
            <strong className="font-semibold text-foreground">{email}</strong>으로 보낸 번호를 입력하세요.
          </p>
        </div>

        <OTPInput value={code} onChange={(v) => { setCode(v); setError(""); }} />

        {error && <p className="text-sm text-destructive" role="alert">{error}</p>}

        <div className="flex items-center justify-between text-sm">
          <span className="text-muted-foreground">
            남은 시간{" "}
            <span className="font-mono font-bold text-destructive" aria-live="polite">{mm}:{ss}</span>
          </span>
          {seconds <= 0 ? (
            <button type="button" onClick={handleResend} className="font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
              인증번호 재전송
            </button>
          ) : (
            <span className="text-muted-foreground">만료 후 재전송 가능</span>
          )}
        </div>

        <Button onClick={handleSubmit} className="w-full">인증하고 계속하기</Button>
      </div>
    </AuthLayout>
  );
}
