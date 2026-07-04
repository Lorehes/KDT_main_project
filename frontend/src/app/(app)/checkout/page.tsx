"use client";

// [목적] 결제 화면(D19/m19) — 7일 무료 체험 Checkout. 카드 등록 여부에 따라 분기
// [이유] MVP 범위: UI mockup까지만. 실결제 연동(카카오페이·카드) 없이 UX 확인용 정적 화면
// [사이드 임팩트] /checkout/new → 카드 미등록 시 이동. 완료 후 /dashboard 복귀
// [수정 시 고려사항] 실결제 연동 시 PG사(카카오페이/토스페이먼츠) SDK 추가 및 별도 Spec 필요.
//   결제 수단 저장은 PCI-DSS 준수 필요 — 카드 번호는 절대 서버에 평문 저장 금지

import Link from "next/link";
import { Shield, ArrowLeft, CreditCard } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";

const PRO_BENEFITS = [
  "무제한 종목 등록",
  "과거 유사 공시 + 주가 반응 차트",
  "상세 해석(영향 이유·예상 방향)",
  "무제한 알림 + 중요도 필터",
  "공시 히스토리 3개월",
];

export default function CheckoutPage() {
  return (
    <div className="mx-auto max-w-lg">
      <Link href="/pricing" className="mb-6 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
        <ArrowLeft className="size-4" aria-hidden />
        요금제
      </Link>

      <div className="flex flex-col gap-5">
        {/* 결제 요약 */}
        <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
          <h1 className="text-xl font-extrabold text-foreground">7일 무료 체험 시작</h1>
          <p className="mt-1 text-sm text-muted-foreground">체험 기간 후 자동으로 Pro 플랜(₩9,900/월)이 시작됩니다.</p>

          <div className="mt-5 flex flex-col divide-y divide-border">
            {[
              { label: "Pro 플랜 (7일 체험)", value: "₩0" },
              { label: "체험 종료 후 (2026.06.16~)", value: "₩9,900/월" },
            ].map(({ label, value }) => (
              <div key={label} className="flex justify-between py-3 text-sm">
                <span className="text-muted-foreground">{label}</span>
                <span className="font-bold text-foreground">{value}</span>
              </div>
            ))}
            <div className="flex justify-between py-3">
              <span className="text-base font-extrabold text-foreground">오늘 결제 금액</span>
              <span className="font-mono text-xl font-extrabold text-primary">₩0</span>
            </div>
          </div>

          <div className="mt-4 rounded-xl bg-muted/50 px-4 py-3 text-xs text-muted-foreground">
            언제든지 해지 가능합니다. 해지 시 체험 기간 내라면 비용이 청구되지 않습니다.
          </div>
        </div>

        {/* 결제 수단 */}
        <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
          <h2 className="mb-4 text-sm font-extrabold text-foreground">결제 수단</h2>

          {/* 등록된 카드 있음 (mockup) */}
          <div className="flex items-center gap-3 rounded-xl border-[1.5px] border-primary bg-primary/5 px-4 py-3.5">
            <CreditCard className="size-5 shrink-0 text-primary" aria-hidden />
            <div className="flex-1">
              <p className="text-sm font-bold text-foreground">신한카드 ····1234</p>
              <p className="text-xs text-muted-foreground">유효기간 12/27</p>
            </div>
            <span className="text-xs font-bold text-primary">선택됨</span>
          </div>

          <div className="mt-2 flex gap-2">
            <Link href="/checkout/new" className={buttonVariants({ variant: "ghost", size: "sm" }) + " text-xs"}>
              다른 카드 사용
            </Link>
            <button
              type="button"
              onClick={() => alert("카카오페이 — 실결제 연동 후 활성화")}
              className={buttonVariants({ variant: "ghost", size: "sm" }) + " gap-1.5 text-xs"}
            >
              <span className="inline-flex size-4 items-center justify-center rounded bg-[#FEE500] text-[8px] font-extrabold text-[#3C1E1E]" aria-hidden>K</span>
              카카오페이
            </button>
          </div>
        </div>

        {/* Pro 혜택 요약 */}
        <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <p className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">Pro 혜택</p>
          <ul className="flex flex-col gap-2" aria-label="Pro 플랜 혜택">
            {PRO_BENEFITS.map((b) => (
              <li key={b} className="flex items-center gap-2 text-sm text-foreground">
                <span className="text-[color:var(--color-sentiment-positive)] font-extrabold" aria-hidden>✓</span>
                {b}
              </li>
            ))}
          </ul>
        </div>

        {/* 보안 고지 */}
        <div className="flex items-center gap-2 text-xs text-muted-foreground">
          <Shield className="size-3.5 shrink-0" aria-hidden />
          결제 정보는 암호화되어 안전하게 처리됩니다.
        </div>

        {/* CTA — MVP mockup */}
        <button
          type="button"
          onClick={() => alert("실결제 연동 후 활성화 예정입니다.")}
          className={buttonVariants() + " w-full py-4 text-base font-extrabold"}
        >
          무료 체험 시작하기 →
        </button>

        <Link href="/dashboard" className="text-center text-sm text-muted-foreground hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
          나중에 결정하기
        </Link>
      </div>
    </div>
  );
}
