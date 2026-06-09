"use client";

// [목적] 공유 카드 화면(D22/m21) — 포트폴리오 주간 요약을 카드 이미지로 공유·다운로드
// [이유] 바이럴 성장 기능. 사용자가 자신의 공시 감지 실적을 SNS에 공유해 신규 유입 유도(통합기획서 §12.2)
// [사이드 임팩트] share-summary API 미존재(Tech Review 확인 필요) — 현재 authStore + portfolios 정적 집계로 대체
// [수정 시 고려사항] 실제 구현 시 html2canvas 또는 Server-Side OG 이미지 생성(Next.js Route Handler) 권장.
//   현재는 CSS 카드 미리보기 + 웹 공유 API(navigator.share) 사용

import Link from "next/link";
import { ArrowLeft, Download, Share2 } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { useAuthStore } from "@/lib/stores/authStore";
import { usePortfolios } from "@/lib/api/portfolios";
import { useDisclosures } from "@/lib/api/disclosures";
import { SentimentBadge } from "@/components/domain/SentimentBadge";

export default function SharePage() {
  const { user } = useAuthStore();
  const { data: portfolios } = usePortfolios();
  const { data: disclosurePage } = useDisclosures({ scope: "portfolio", size: 10 });

  const disclosures = disclosurePage?.content ?? [];
  const positiveCount = disclosures.filter((d) => d.sentiment === "POSITIVE").length;
  const negativeCount = disclosures.filter((d) => d.sentiment === "NEGATIVE").length;
  const today = new Date().toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric" });

  const handleShare = async () => {
    const text = `공시레이더 주간 리포트 — ${user?.nickname}님의 포트폴리오\n호재 ${positiveCount}건 · 악재 ${negativeCount}건 · 보유 ${portfolios?.length ?? 0}개 종목\n공시레이더로 내 종목 공시를 AI가 즉시 해석해드립니다.`;
    if (navigator.share) {
      await navigator.share({ title: "공시레이더 주간 리포트", text });
    } else {
      await navigator.clipboard.writeText(text);
      alert("텍스트가 클립보드에 복사되었습니다.");
    }
  };

  return (
    <div className="mx-auto max-w-md">
      <Link href="/dashboard" className="mb-6 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
        <ArrowLeft className="size-4" aria-hidden />
        대시보드
      </Link>

      <h1 className="mb-5 text-2xl font-extrabold tracking-tight text-foreground">공유 카드</h1>

      {/* 공유 카드 프리뷰 */}
      <div
        id="share-card"
        className="overflow-hidden rounded-2xl bg-gradient-to-br from-[color:var(--color-brand-navy)] to-[#163a63] p-7 text-white shadow-2xl"
        aria-label="공유 카드 미리보기"
      >
        {/* 브랜드 */}
        <div className="mb-5 flex items-center gap-2">
          <div className="grid size-8 place-items-center rounded-lg bg-white/10">
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="var(--color-brand-sky)" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
              <circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="3.8"/><path d="M12 12 18.5 7.5"/>
            </svg>
          </div>
          <span className="text-sm font-extrabold tracking-tight">공시레이더</span>
        </div>

        <p className="mb-1 text-xs font-semibold text-blue-300">주간 공시 리포트</p>
        <h2 className="mb-4 text-xl font-extrabold">{user?.nickname ?? "투자자"}님의 포트폴리오</h2>

        {/* 통계 */}
        <div className="mb-5 flex gap-3">
          {[
            { label: "호재", value: positiveCount, type: "POSITIVE" as const },
            { label: "악재", value: negativeCount, type: "NEGATIVE" as const },
            { label: "보유 종목", value: portfolios?.length ?? 0, type: null },
          ].map(({ label, value, type }) => (
            <div key={label} className="flex-1 rounded-xl border border-white/10 bg-white/7 p-3.5">
              <p className="text-[11px] text-blue-300">{label}</p>
              <div className="mt-1.5 flex items-end gap-1.5">
                {type && <SentimentBadge sentiment={type} size="sm" className="pointer-events-none" />}
                <span className="font-mono text-2xl font-extrabold leading-none">{value}</span>
                <span className="mb-0.5 text-xs text-blue-300">건</span>
              </div>
            </div>
          ))}
        </div>

        {/* 최근 공시 3건 */}
        {disclosures.slice(0, 3).map((d) => (
          <div key={d.id} className="flex items-center justify-between border-t border-white/10 py-2.5">
            <div className="min-w-0 flex-1">
              <p className="text-sm font-bold">{d.corp_name}</p>
              <p className="truncate text-xs text-blue-300">{d.report_nm}</p>
            </div>
            {d.sentiment && <SentimentBadge sentiment={d.sentiment} isWithheld={d.is_withheld} size="sm" className="shrink-0" />}
          </div>
        ))}

        <p className="mt-4 text-[10px] text-blue-400">{today} · 본 정보는 AI 분석 결과이며 투자 자문이 아닙니다.</p>
      </div>

      {/* 공유 버튼 */}
      <div className="mt-5 flex gap-3">
        <button
          type="button"
          onClick={() => alert("이미지 다운로드는 html2canvas 연동 후 활성화 예정")}
          className={buttonVariants({ variant: "outline" }) + " flex-1 gap-2"}
          aria-label="카드 이미지 다운로드"
        >
          <Download className="size-4" aria-hidden />
          이미지 저장
        </button>
        <button
          type="button"
          onClick={handleShare}
          className={buttonVariants() + " flex-1 gap-2"}
          aria-label="카드 공유"
        >
          <Share2 className="size-4" aria-hidden />
          공유하기
        </button>
      </div>

      <p className="mt-3 text-center text-xs text-muted-foreground">
        이미지 저장은 html2canvas 연동 후 활성화됩니다. (W7 추가 개선 예정)
      </p>
    </div>
  );
}
