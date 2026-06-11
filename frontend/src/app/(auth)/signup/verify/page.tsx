"use client";

// [목적] 이메일 인증 화면(D6/m10, STEP 1/4) — 6자리 OTP 입력·타이머·재전송
// [이유] 이메일 소유 확인 후 다음 단계(약관 동의)로 이동. POST /auth/email/verify 검증 성공 시 verifiedEmailCache 마커 등록.
// [사이드 임팩트] signupStore.email이 비어 있으면 /signup으로 복귀(직접 접근 방어).
//   410(만료) → 타이머 리셋 + 에러. 400(불일치) → 에러. 성공 → /signup/terms 이동.
// [수정 시 고려사항] verifiedEmailCache 10분 TTL — 인증 후 10분 내에 signup 완료 필요.

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { toast } from "sonner";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { OTPInput } from "@/components/domain/OTPInput";
import { Button } from "@/components/ui/button";
import { useSignupStore } from "@/lib/stores/signupStore";
import { useSendEmailOtp, useVerifyEmailOtp } from "@/lib/api/auth";
import { ApiException } from "@/lib/api/client";

const TIMER_SECONDS = 5 * 60;

export default function VerifyPage() {
  const router = useRouter();
  const { email } = useSignupStore();
  const [code, setCode] = useState("");
  const [seconds, setSeconds] = useState(TIMER_SECONDS);
  const [error, setError] = useState("");

  const sendEmailOtp   = useSendEmailOtp();
  const verifyEmailOtp = useVerifyEmailOtp();

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

  const handleSubmit = async () => {
    if (code.length < 6) { setError("6자리 인증번호를 모두 입력해주세요."); return; }
    try {
      await verifyEmailOtp.mutateAsync({ email, code });
      router.push("/signup/terms");
    } catch (e) {
      const status = e instanceof ApiException ? e.body.status : 0;
      if (status === 410) {
        setError("인증번호가 만료됐습니다. 재전송해주세요.");
        setSeconds(0);
      } else {
        setError("인증번호가 일치하지 않습니다.");
      }
    }
  };

  const handleResend = async () => {
    try {
      await sendEmailOtp.mutateAsync(email);
      setSeconds(TIMER_SECONDS);
      setCode("");
      setError("");
    } catch (e) {
      const status = e instanceof ApiException ? e.body.status : 0;
      if (status === 429) {
        toast.error("잠시 후 다시 시도해주세요. (발송 횟수 초과)");
      } else {
        toast.error("재전송에 실패했습니다. 다시 시도해주세요.");
      }
    }
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
            <button
              type="button"
              onClick={handleResend}
              disabled={sendEmailOtp.isPending}
              className="font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50"
            >
              {sendEmailOtp.isPending ? "발송 중…" : "인증번호 재전송"}
            </button>
          ) : (
            <span className="text-muted-foreground">만료 후 재전송 가능</span>
          )}
        </div>

        <Button onClick={handleSubmit} disabled={verifyEmailOtp.isPending} className="w-full">
          {verifyEmailOtp.isPending ? "확인 중…" : "인증하고 계속하기"}
        </Button>
      </div>
    </AuthLayout>
  );
}
