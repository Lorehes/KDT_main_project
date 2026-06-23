// [목적] 약관 동의 체크박스 리스트 UI — 이메일·소셜 가입 양쪽에서 공유하는 순수 UI 컴포넌트.
// [이유] 기존 terms/page.tsx 한 파일에 이메일·소셜 플로우가 혼합돼 있어(M-M1) 분화 시 수정 폭발 위험.
//   공유 UI를 추출해 두 플로우가 독립적으로 진화할 수 있도록 함.
// [사이드 임팩트] 이 컴포넌트는 상태를 외부에서 주입받는다(checked/onChange).
//   TERMS_ITEMS 배열이 이 파일의 SSOT — 약관 항목 추가·변경 시 여기만 수정하면 두 플로우 모두 반영.
// [수정 시 고려사항] AGE 항목은 UI 전용(API에 전송하지 않음) — apiKey가 없는 항목은 호출자가 필터링.
//   필수 동의 항목 추가 시 TERMS_ITEMS에 required:true로 추가하면 충분.
//   약관 본문 링크(현재 "#")는 실제 URL로 교체 필요.

"use client";

import { cn } from "@/lib/utils";

export type TermsKey = "TERMS" | "PRIVACY" | "DISCLAIMER" | "AGE" | "MARKETING";

export interface TermsItem {
  key: TermsKey;
  label: string;
  required: boolean;
  link?: string;
  apiKey?: "termsAgreed" | "privacyAgreed" | "disclaimerAgreed" | "marketingAgreed";
}

export const TERMS_ITEMS: TermsItem[] = [
  { key: "TERMS",      label: "서비스 이용약관",                               required: true,  link: "#", apiKey: "termsAgreed" },
  { key: "PRIVACY",    label: "개인정보 수집·이용 동의",                         required: true,  link: "#", apiKey: "privacyAgreed" },
  { key: "DISCLAIMER", label: "본 서비스는 투자 자문이 아닌 정보 제공 도구임에 동의\n(자본시장법 제6조·제17조)", required: true, link: "#", apiKey: "disclaimerAgreed" },
  { key: "AGE",        label: "만 14세 이상입니다",                              required: true },
  { key: "MARKETING",  label: "마케팅 정보 수신 (혜택·이벤트)",                  required: false, apiKey: "marketingAgreed" },
];

interface TermsCheckboxListProps {
  checked: Record<string, boolean>;
  onChange: (checked: Record<string, boolean>) => void;
}

export function TermsCheckboxList({ checked, onChange }: TermsCheckboxListProps) {
  const requiredItems = TERMS_ITEMS.filter((t) => t.required);
  const requiredDone = requiredItems.every((t) => checked[t.key]);
  const someRequired = requiredItems.some((t) => checked[t.key]);
  const partialRequired = someRequired && !requiredDone;

  const toggle = (key: string) => onChange({ ...checked, [key]: !checked[key] });

  const toggleAll = () => {
    const next = { ...checked };
    requiredItems.forEach((t) => { next[t.key] = !requiredDone; });
    onChange(next);
  };

  return (
    <div className="rounded-2xl border border-border bg-card p-1">
      {/* 필수 전체 동의 */}
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
              <span className={cn(
                "mr-1.5 inline-block rounded-md px-1.5 py-0.5 text-[10px] font-extrabold",
                required ? "bg-destructive/10 text-destructive" : "bg-muted text-muted-foreground",
              )}>
                {required ? "[필수]" : "[선택]"}
              </span>
              {label}
            </p>
            {link && (
              <a
                href={link}
                className="shrink-0 text-xs text-muted-foreground underline hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                aria-label={`${label} 약관 보기`}
              >
                보기
              </a>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}

export function Checkbox({ checked }: { checked: boolean }) {
  return (
    <span
      className={cn(
        "grid size-6 place-items-center rounded-[7px] border-2 transition-colors",
        checked ? "border-primary bg-primary" : "border-border bg-background",
      )}
      aria-hidden
    >
      {checked && (
        <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2.8" strokeLinecap="round" strokeLinejoin="round">
          <path d="M5 12.5 10 17.5 19 7" />
        </svg>
      )}
    </span>
  );
}
