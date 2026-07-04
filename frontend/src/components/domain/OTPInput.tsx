"use client";

// [목적] 6칸 OTP 입력 — 자동 포커스 이동·붙여넣기 지원
// [이유] 이메일 인증·휴대폰 인증 단계에서 코드 입력 UX 최적화
// [사이드 임팩트] 부모에서 onChange(code: string) 콜백으로 최종 6자리 문자열 수신
// [수정 시 고려사항] 붙여넣기 시 전체 코드가 분산 입력되도록 onPaste 처리.
//   모바일 키패드 최적화를 위해 inputMode="numeric" 사용

import { useRef, KeyboardEvent, ClipboardEvent } from "react";
import { cn } from "@/lib/utils";

interface OTPInputProps {
  value: string;
  onChange: (code: string) => void;
  disabled?: boolean;
  className?: string;
}

const LENGTH = 6;

export function OTPInput({ value, onChange, disabled, className }: OTPInputProps) {
  const inputs = useRef<(HTMLInputElement | null)[]>([]);
  const digits = value.padEnd(LENGTH, "").split("").slice(0, LENGTH);

  const update = (index: number, char: string) => {
    const arr = [...digits];
    arr[index] = char;
    onChange(arr.join("").trimEnd());
    if (char && index < LENGTH - 1) inputs.current[index + 1]?.focus();
  };

  const handleKey = (index: number, e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Backspace" && !digits[index] && index > 0) {
      inputs.current[index - 1]?.focus();
    }
  };

  const handlePaste = (e: ClipboardEvent<HTMLInputElement>) => {
    e.preventDefault();
    const pasted = e.clipboardData.getData("text").replace(/\D/g, "").slice(0, LENGTH);
    onChange(pasted);
    inputs.current[Math.min(pasted.length, LENGTH - 1)]?.focus();
  };

  return (
    <div className={cn("flex gap-2 sm:gap-2.5", className)} role="group" aria-label="6자리 인증번호 입력">
      {Array.from({ length: LENGTH }).map((_, i) => (
        <input
          key={i}
          ref={(el) => { inputs.current[i] = el; }}
          type="text"
          inputMode="numeric"
          pattern="[0-9]*"
          maxLength={1}
          value={digits[i] ?? ""}
          disabled={disabled}
          onChange={(e) => update(i, e.target.value.replace(/\D/g, "").slice(-1))}
          onKeyDown={(e) => handleKey(i, e)}
          onPaste={handlePaste}
          aria-label={`인증번호 ${i + 1}번째 자리`}
          className={cn(
            "font-mono h-12 w-11 shrink-0 rounded-xl border text-center text-xl font-bold text-foreground transition-colors focus:outline-none focus-visible:border-primary focus-visible:ring-2 focus-visible:ring-primary/40 sm:h-14 sm:w-12 sm:text-2xl",
            digits[i] ? "border-primary bg-primary/5" : "border-border bg-background",
            disabled && "opacity-50",
          )}
        />
      ))}
    </div>
  );
}
