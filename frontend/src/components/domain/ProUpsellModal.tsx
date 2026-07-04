"use client";

// [목적] Pro 업셀 모달 — 잠금 기능 클릭 시 업그레이드 유도
// [이유] TierGate에서 트리거. 전역 uiStore.upsellModalOpen으로 어디서든 열 수 있음
// [사이드 임팩트] uiStore.upsellModalOpen을 구독. (app)/layout.tsx에 한 번만 렌더
// [수정 시 고려사항] Pro 혜택 목록은 정적. 추후 /pricing/plans API 연동 가능

import { useUIStore } from "@/lib/stores/uiStore";
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from "@/components/ui/dialog";
import { Button, buttonVariants } from "@/components/ui/button";
import { Check, TrendingUp } from "lucide-react";
import Link from "next/link";

const PRO_BENEFITS = [
  "무제한 종목 등록",
  "과거 유사 공시 + 주가 반응 차트",
  "상세 해석 (영향 이유·예상 방향)",
  "무제한 알림 + 중요도 필터",
  "공시 히스토리 3개월",
];

export function ProUpsellModal() {
  const { upsellModalOpen, setUpsellModalOpen } = useUIStore();

  return (
    <Dialog open={upsellModalOpen} onOpenChange={setUpsellModalOpen}>
      <DialogContent className="max-w-md gap-6">
        <DialogHeader>
          <div className="mx-auto mb-2 flex size-14 items-center justify-center rounded-2xl bg-primary/10">
            <TrendingUp className="size-7 text-primary" aria-hidden />
          </div>
          <DialogTitle className="text-center text-xl">Pro 플랜으로 업그레이드</DialogTitle>
          <DialogDescription className="text-center">
            과거 패턴·주가 반응 분석으로 더 깊은 인사이트를 얻으세요.
          </DialogDescription>
        </DialogHeader>

        <ul className="flex flex-col gap-2.5" aria-label="Pro 혜택">
          {PRO_BENEFITS.map((b) => (
            <li key={b} className="flex items-center gap-2.5 text-sm">
              <Check className="size-4 shrink-0 text-[color:var(--color-sentiment-positive)]" aria-hidden />
              {b}
            </li>
          ))}
        </ul>

        <div className="flex flex-col gap-2.5">
          <Link
            href="/checkout"
            className={buttonVariants() + " w-full"}
            onClick={() => setUpsellModalOpen(false)}
          >
            7일 무료 체험 시작 →
          </Link>
          <Link
            href="/pricing"
            className={buttonVariants({ variant: "ghost" }) + " w-full"}
            onClick={() => setUpsellModalOpen(false)}
          >
            요금제 비교 보기
          </Link>
        </div>
      </DialogContent>
    </Dialog>
  );
}
