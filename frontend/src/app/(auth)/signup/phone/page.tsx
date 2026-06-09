"use client";

// [목적] 휴대폰 인증 화면(D8/m12, STEP 3/4) — 알림톡 수신 번호 인증. "나중에" 스킵 허용
// [이유] 카카오 알림톡 발송을 위한 번호 인증. 선택 사항이므로 스킵 시 이메일로 대체
// [사이드 임팩트] POST /users/me/phone/verify 호출. 성공·스킵 모두 /signup/profile로 이동
// [수정 시 고려사항] 번호는 평문 콘솔 출력 금지(개인정보). 백엔드에서 AES-256-GCM 암호화 저장

import { useState, useEffect } from "react";
import { useRouter } from "next/navigation";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { OnboardingStepper } from "@/components/layout/OnboardingStepper";
import { OTPInput } from "@/components/domain/OTPInput";
import { Button } from "@/components/ui/button";
import { useAuthStore } from "@/lib/stores/authStore";

const TIMER_SECONDS = 3 * 60;

export default function PhonePage() {
  const router = useRouter();
  const { user } = useAuthStore();
  const [phone, setPhone] = useState("");
  const [code, setCode] = useState("");
  const [step, setStep] = useState<"phone" | "code">("phone");
  const [seconds, setSeconds] = useState(TIMER_SECONDS);
  const [phoneError, setPhoneError] = useState("");
  const [codeError, setCodeError] = useState("");

  useEffect(() => {
    if (step !== "code" || seconds <= 0) return;
    const t = setTimeout(() => setSeconds((s) => s - 1), 1000);
    return () => clearTimeout(t);
  }, [step, seconds]);

  const handlePhoneSubmit = () => {
    const cleaned = phone.replace(/\D/g, "");
    if (!/^010\d{8}$/.test(cleaned)) {
      setPhoneError("010으로 시작하는 11자리 번호를 입력해주세요.");
      return;
    }
    setPhoneError("");
    setStep("code");
    setSeconds(TIMER_SECONDS);
    // TODO: POST /users/me/phone/verify — 인증번호 발송
  };

  const handleVerify = () => {
    if (code.length < 6) { setCodeError("6자리 인증번호를 모두 입력해주세요."); return; }
    // TODO: POST /users/me/phone/verify — 코드 검증
    router.push("/signup/profile");
  };

  const mm = String(Math.floor(seconds / 60)).padStart(2, "0");
  const ss = String(seconds % 60).padStart(2, "0");

  return (
    <AuthLayout
      heading={<>알림톡<br /><span className="text-[color:var(--color-brand-sky)]">받을 번호</span></>}
      subtext="카카오 알림톡(오픈율 40~60%) 발송을 위해 휴대폰 인증이 필요해요. 선택 시 이메일로 대체됩니다."
    >
      <div className="flex flex-col gap-7">
        <OnboardingStepper currentStep={3} />
        <h2 className="text-2xl font-extrabold tracking-tight text-foreground">휴대폰 인증</h2>

        {/* 번호 입력 */}
        <div className="flex flex-col gap-1.5">
          <label htmlFor="phone" className="text-sm font-semibold text-foreground">휴대폰 번호</label>
          <div className="flex gap-2.5">
            <input
              id="phone"
              type="tel"
              inputMode="numeric"
              placeholder="010-0000-0000"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
              disabled={step === "code"}
              aria-invalid={!!phoneError}
              aria-describedby={phoneError ? "phone-error" : undefined}
              className="flex-1 rounded-xl border border-border bg-background px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:border-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/20 aria-invalid:border-destructive disabled:opacity-60"
            />
            <Button type="button" onClick={handlePhoneSubmit} disabled={step === "code"} size="sm" className="shrink-0 self-stretch px-4">
              인증요청
            </Button>
          </div>
          {phoneError && <p id="phone-error" className="text-xs text-destructive" role="alert">{phoneError}</p>}
        </div>

        {step === "code" && (
          <>
            <div className="flex flex-col gap-3">
              <label className="text-sm font-semibold text-foreground">인증번호</label>
              <OTPInput value={code} onChange={(v) => { setCode(v); setCodeError(""); }} />
              {codeError && <p className="text-xs text-destructive" role="alert">{codeError}</p>}
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  남은 시간 <span className="font-mono font-bold text-destructive" aria-live="polite">{mm}:{ss}</span>
                </span>
                {seconds <= 0 && (
                  <button type="button" onClick={() => { setStep("phone"); setSeconds(TIMER_SECONDS); }}
                    className="font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
                    재전송
                  </button>
                )}
              </div>
            </div>

            {/* 카카오 채널 추가 안내 */}
            <div className="flex items-center gap-3 rounded-xl border border-[#F2D879] bg-[#FEF6CC] p-4">
              <div className="grid size-9 shrink-0 place-items-center rounded-lg bg-[#FEE500]" aria-hidden>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="#3C1E1E" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M20.5 12a8 8 0 0 1-11.5 7.2L4 21l1.8-4.5A8 8 0 1 1 20.5 12z" />
                </svg>
              </div>
              <div>
                <p className="text-sm font-bold text-foreground">공시레이더 채널 추가</p>
                <p className="text-xs text-muted-foreground">알림톡을 받으려면 카카오 채널을 추가해야 해요.</p>
              </div>
            </div>

            <Button onClick={handleVerify} className="w-full">인증 완료하고 계속하기</Button>
          </>
        )}

        <button
          type="button"
          onClick={() => router.push("/signup/profile")}
          className="text-center text-sm font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
          나중에 할게요 (이메일로 받기)
        </button>
      </div>
    </AuthLayout>
  );
}
