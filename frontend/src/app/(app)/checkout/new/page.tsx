"use client";

// [목적] 신규 카드 등록 결제 화면(D20/m19b) — 카드 번호·유효기간 입력 + 카카오페이 선택
// [이유] /checkout에서 "다른 카드 사용" 클릭 시 진입. MVP mockup — 실결제 미연동
// [사이드 임팩트] 없음 (완전 정적 mockup)
// [수정 시 고려사항] 실제 구현 시 카드 번호는 PG사 SDK iframe으로만 입력받아야 함(PCI-DSS).
//   절대 카드 번호를 자체 서버에 전송·저장 금지

import Link from "next/link";
import { ArrowLeft, CreditCard } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";

export default function NewCardCheckoutPage() {
  return (
    <div className="mx-auto max-w-lg">
      <Link href="/checkout" className="mb-6 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
        <ArrowLeft className="size-4" aria-hidden />
        결제로 돌아가기
      </Link>

      <div className="flex flex-col gap-5">
        <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
          <h1 className="mb-5 text-xl font-extrabold text-foreground">카드 정보 입력</h1>

          {/* PCI-DSS 고지 */}
          <div className="mb-5 flex items-start gap-2.5 rounded-xl bg-primary/5 px-4 py-3 text-xs text-muted-foreground">
            <CreditCard className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
            <p>카드 정보는 PG사(카카오페이/토스페이먼츠)를 통해 암호화 처리됩니다. 서비스 서버에는 저장되지 않습니다.</p>
          </div>

          {/* 카드 번호 — UI mockup (실제 구현은 PG SDK iframe) */}
          <div className="flex flex-col gap-4">
            <div className="flex flex-col gap-1.5">
              <label className="text-sm font-semibold text-foreground">카드 번호</label>
              <div className="rounded-xl border border-border bg-muted/30 px-4 py-3 text-sm text-muted-foreground">
                실결제 연동 후 PG사 SDK 입력창으로 교체 예정
              </div>
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-semibold text-foreground">유효기간</label>
                <div className="rounded-xl border border-border bg-muted/30 px-4 py-3 text-sm text-muted-foreground">MM / YY</div>
              </div>
              <div className="flex flex-col gap-1.5">
                <label className="text-sm font-semibold text-foreground">CVC</label>
                <div className="rounded-xl border border-border bg-muted/30 px-4 py-3 text-sm text-muted-foreground">···</div>
              </div>
            </div>
          </div>
        </div>

        {/* 카카오페이 대안 */}
        <button
          type="button"
          onClick={() => alert("카카오페이 — 실결제 연동 후 활성화")}
          className="flex w-full items-center justify-center gap-2.5 rounded-xl bg-[#FEE500] py-3.5 text-sm font-extrabold text-[#3C1E1E] transition-opacity hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label="카카오페이로 결제"
        >
          <span className="grid size-5 place-items-center rounded bg-[#3C1E1E] text-[10px] font-extrabold text-[#FEE500]" aria-hidden>K</span>
          카카오페이로 결제
        </button>

        <button
          type="button"
          onClick={() => alert("실결제 연동 후 활성화 예정입니다.")}
          className={buttonVariants() + " w-full"}
        >
          카드로 결제하기
        </button>
      </div>
    </div>
  );
}
