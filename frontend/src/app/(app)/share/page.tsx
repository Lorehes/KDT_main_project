"use client";

// [목적] 공유 카드 화면(D22/m21) — 포트폴리오 주간 요약을 PNG 이미지로 캡처·다운로드·SNS 파일 공유
// [이유] 바이럴 성장 기능 (통합기획서 §12.2). html2canvas-pro를 사용하는 이유: Tailwind 4 기본 색상계
//   oklch()/color() + CSS 변수를 구버전 html2canvas가 파싱 못해 카드가 검정/투명으로 깨지는 P0 문제 해결.
// [사이드 임팩트] document.fonts.ready 대기 때문에 첫 캡처 응답이 폰트 로드 상태에 따라 0~수백ms 지연.
//   캡처 중 두 버튼 모두 aria-busy+disabled 처리. share-summary 실 API 없음 — 현재 클라이언트 집계 유지.
// [수정 시 고려사항] R5(GET /users/me/share-summary) 추가 시 usePortfolios/useDisclosures 집계를 교체(별도 wave).
//   자본시장법 §11.1: #share-card 내 면책 문구(하단 p태그)를 캡처 영역 밖으로 옮기지 말 것.
//   scale:2는 Retina 고해상도 대응 — 파일 크기 증가 시 조정 가능.

import { useState, useMemo } from "react";
import Link from "next/link";
import { ArrowLeft, Download, Share2 } from "lucide-react";
import { toast } from "sonner";
import html2canvas from "html2canvas-pro";
import { buttonVariants } from "@/components/ui/button";
import { useAuthStore } from "@/lib/stores/authStore";
import { usePortfolios } from "@/lib/api/portfolios";
import { useDisclosures } from "@/lib/api/disclosures";
import { SentimentBadge } from "@/components/domain/SentimentBadge";

async function captureShareCard(): Promise<Blob> {
  await document.fonts.ready;
  const card = document.getElementById("share-card");
  if (!card) throw new Error("share-card 요소를 찾을 수 없습니다.");
  const canvas = await html2canvas(card, {
    backgroundColor: null,
    scale: 2,
    useCORS: true,
  });
  return new Promise<Blob>((resolve, reject) => {
    canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error("이미지 변환에 실패했습니다."));
    }, "image/png");
  });
}

function triggerDownload(blob: Blob) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = "공시레이더_주간리포트.png";
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  setTimeout(() => URL.revokeObjectURL(url), 1000); // Firefox: 다운로드 시작 후 해제
}

export default function SharePage() {
  const { user } = useAuthStore();
  const { data: portfolios } = usePortfolios();
  const { data: disclosurePage } = useDisclosures({ scope: "portfolio", size: 10 });
  const [capturing, setCapturing] = useState(false);

  const disclosures = disclosurePage?.content ?? [];
  const positiveCount = disclosures.filter((d) => d.sentiment === "POSITIVE").length;
  const negativeCount = disclosures.filter((d) => d.sentiment === "NEGATIVE").length;
  const today = useMemo(
    () => new Date().toLocaleDateString("ko-KR", { year: "numeric", month: "long", day: "numeric" }),
    [],
  );

  const handleDownload = async () => {
    setCapturing(true);
    try {
      const blob = await captureShareCard();
      triggerDownload(blob);
      toast.success("이미지가 저장됐습니다.");
    } catch (err) {
      toast.error("이미지 저장에 실패했습니다.");
      console.error("[SharePage] 이미지 저장 실패:", err);
    } finally {
      setCapturing(false);
    }
  };

  const handleShareImage = async () => {
    setCapturing(true);
    try {
      const blob = await captureShareCard();
      const file = new File([blob], "공시레이더_주간리포트.png", { type: "image/png" });
      if (navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: "공시레이더 주간 리포트" });
      } else if (navigator.share) {
        // 파일 공유 미지원 기기 — 텍스트 폴백
        const text = `공시레이더 주간 리포트 — ${user?.nickname}님의 포트폴리오\n호재 ${positiveCount}건 · 악재 ${negativeCount}건 · 보유 ${portfolios?.length ?? 0}개 종목`;
        await navigator.share({ title: "공시레이더 주간 리포트", text });
      } else {
        // Web Share API 자체 미지원(데스크톱 등) — 다운로드 폴백
        triggerDownload(blob);
        toast.success("이미지가 저장됐습니다. (이 기기에서는 공유 대신 다운로드로 제공됩니다)");
      }
    } catch (err) {
      if (err instanceof Error && err.name !== "AbortError") {
        toast.error("공유에 실패했습니다.");
      }
    } finally {
      setCapturing(false);
    }
  };

  return (
    <div className="mx-auto max-w-md">
      <Link
        href="/dashboard"
        className="mb-6 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
      >
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
            <svg
              width="16"
              height="16"
              viewBox="0 0 24 24"
              fill="none"
              stroke="var(--color-brand-sky)"
              strokeWidth="1.8"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden
            >
              <circle cx="12" cy="12" r="8.5" />
              <circle cx="12" cy="12" r="3.8" />
              <path d="M12 12 18.5 7.5" />
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
                {type && (
                  <SentimentBadge sentiment={type} size="sm" className="pointer-events-none" />
                )}
                <span className="font-mono text-2xl font-extrabold leading-none">{value}</span>
                <span className="mb-0.5 text-xs text-blue-300">건</span>
              </div>
            </div>
          ))}
        </div>

        {/* 최근 공시 3건 */}
        {disclosures.slice(0, 3).map((d) => (
          <div
            key={d.id}
            className="flex items-center justify-between border-t border-white/10 py-2.5"
          >
            <div className="min-w-0 flex-1">
              <p className="text-sm font-bold">{d.corp_name}</p>
              <p className="truncate text-xs text-blue-300">{d.report_nm}</p>
            </div>
            {d.sentiment && (
              <SentimentBadge
                sentiment={d.sentiment}
                isWithheld={d.is_withheld}
                size="sm"
                className="shrink-0"
              />
            )}
          </div>
        ))}

        <p className="mt-4 text-[10px] text-blue-400">
          {today} · 본 정보는 AI 분석 결과이며 투자 자문이 아닙니다.
        </p>
      </div>

      {/* 스크린리더 캡처 상태 알림 */}
      <div role="status" aria-live="polite" className="sr-only">
        {capturing ? "이미지 생성 중입니다." : ""}
      </div>

      {/* 공유 버튼 */}
      <div className="mt-5 flex gap-3">
        <button
          type="button"
          onClick={handleDownload}
          disabled={capturing}
          aria-busy={capturing}
          aria-label="카드 이미지 다운로드"
          className={buttonVariants({ variant: "outline" }) + " flex-1 gap-2 disabled:opacity-60"}
        >
          <Download className="size-4" aria-hidden />
          {capturing ? "처리 중…" : "이미지 저장"}
        </button>
        <button
          type="button"
          onClick={handleShareImage}
          disabled={capturing}
          aria-busy={capturing}
          aria-label="카드 이미지 공유"
          className={buttonVariants() + " flex-1 gap-2 disabled:opacity-60"}
        >
          <Share2 className="size-4" aria-hidden />
          {capturing ? "처리 중…" : "공유하기"}
        </button>
      </div>
    </div>
  );
}
