// [목적] 서비스 랜딩 페이지(D1/m01) — 가치 제안·CTA·기능 소개·면책 고지
// [이유] 비로그인 방문자에게 공시레이더의 핵심 가치를 소개하고 가입·요금제로 유도하는 마케팅 LP
// [사이드 임팩트] PublicNavbar는 (public) 그룹 layout이 아닌 직접 import — 루트 page.tsx는 (public) 그룹 밖이기 때문
// [수정 시 고려사항] 로그인 세션 있으면 클라이언트에서 /dashboard 리다이렉트(LandingRedirect 컴포넌트).
//   히어로 우측 대시보드 미리보기 이미지는 W4 완료 후 실제 스크린샷으로 교체 예정.
//   투자 권유 표현("매수/매도 추천", "수익 보장") 절대 금지 — 자본시장법 §11.1

import type { Metadata } from "next";
import Link from "next/link";
import {
  FileText,
  Zap,
  TrendingUp,
  ShieldCheck,
  ChevronRight,
} from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { PublicNavbar } from "@/components/layout/PublicNavbar";
import { DisclaimerNotice } from "@/components/domain/DisclaimerNotice";
import { LandingRedirect } from "./LandingRedirect";

export const metadata: Metadata = {
  title: "공시레이더 — DART 공시를 호재·악재로 즉시 해석",
  description:
    "보유 종목의 DART 공시를 AI가 호재·중립·악재로 판별해 카카오 알림톡과 웹 대시보드로 알려드립니다. 무료로 시작하세요.",
};

const FEATURES = [
  {
    icon: FileText,
    title: "원문 → 자연어",
    desc: "복잡한 공시 서식을 한 줄 의미로 변환합니다.",
  },
  {
    icon: Zap,
    title: "0.5초 판별",
    desc: "호재·악재 배지를 카드 최상단에 고대비로 표시합니다.",
  },
  {
    icon: TrendingUp,
    title: "과거 패턴",
    desc: "같은 유형 공시에 대한 평균 주가 반응 데이터를 제공합니다.",
  },
  {
    icon: ShieldCheck,
    title: "솔직한 한계",
    desc: "신뢰도가 낮으면 '판단 보류'로 표시해 과신을 막습니다.",
  },
];

export default function LandingPage() {
  return (
    <>
      <LandingRedirect />
      <PublicNavbar />

      <main>
        {/* ── 히어로 ── */}
        <section
          className="bg-[color:var(--color-brand-navy)] px-6 py-16 md:px-20 md:py-[72px]"
          aria-labelledby="hero-heading"
        >
          <div className="mx-auto grid max-w-7xl items-center gap-12 md:grid-cols-[1.1fr_0.9fr]">
            <div>
              {/* 필 배지 */}
              <span className="mb-6 inline-flex items-center gap-2 rounded-full bg-white/10 px-4 py-2 text-sm font-semibold text-blue-200">
                <span
                  className="size-2 rounded-full bg-[color:var(--color-sentiment-positive)]"
                  aria-hidden
                />
                DART 실시간 · 30초 이내 해석
              </span>

              <h1
                id="hero-heading"
                className="text-5xl font-extrabold leading-[1.06] tracking-tight text-white md:text-[50px]"
              >
                공시가 떴습니다.
                <br />
                <span className="text-[color:var(--color-brand-sky)]">
                  호재일까요, 악재일까요?
                </span>
              </h1>

              <p className="mt-6 max-w-[48ch] text-lg leading-relaxed text-blue-200">
                보유 종목의 DART 공시를 AI가 호재·중립·악재로 판별해,
                카카오 알림톡과 웹 대시보드로 요약을 보내드립니다.
              </p>

              <div className="mt-8 flex flex-wrap gap-3">
                <Link
                  href="/signup"
                  className={buttonVariants({ size: "lg" }) + " gap-2 px-7"}
                >
                  무료로 시작하기
                  <ChevronRight className="size-4" aria-hidden />
                </Link>
                <Link
                  href="/dashboard"
                  className={
                    buttonVariants({ variant: "ghost", size: "lg" }) +
                    " border border-white/20 px-7 text-white hover:bg-white/10 hover:text-white"
                  }
                >
                  대시보드 둘러보기
                </Link>
              </div>
            </div>

            {/* 대시보드 미리보기 placeholder — W4 완료 후 실제 이미지로 교체 */}
            <div
              className="hero-preview hidden h-[380px] flex-col items-center justify-center gap-3 rounded-2xl border border-white/10 bg-white/5 md:flex"
              aria-label="대시보드 미리보기 이미지 (준비 중)"
              role="img"
            >
              <div className="flex flex-col items-center gap-2 opacity-40">
                <div className="flex gap-2">
                  {["호재", "중립", "악재"].map((label, i) => (
                    <span key={label} className={`rounded-md px-2.5 py-1 text-[11px] font-extrabold text-white ${i === 0 ? "bg-[color:var(--color-sentiment-positive)]" : i === 1 ? "bg-[color:var(--color-sentiment-neutral)]" : "bg-[color:var(--color-sentiment-negative)]"}`}>{label}</span>
                  ))}
                </div>
                <p className="font-mono text-[11px] text-blue-300/70">[ 대시보드 미리보기 ]</p>
              </div>
            </div>
          </div>
        </section>

        {/* ── Why 공시레이더 ── */}
        <section
          className="bg-background px-6 py-16 md:px-20 md:py-20"
          aria-labelledby="why-heading"
        >
          <div className="mx-auto max-w-7xl">
            <div className="mb-12 text-center">
              <p className="text-xs font-extrabold uppercase tracking-widest text-primary">
                Why 공시레이더
              </p>
              <h2
                id="why-heading"
                className="mt-3 text-3xl font-extrabold tracking-tight text-foreground md:text-[32px]"
              >
                복잡한 공시를, 판단으로.
              </h2>
            </div>

            <ul
              className="grid gap-5 sm:grid-cols-2 lg:grid-cols-4"
              aria-label="주요 기능"
            >
              {FEATURES.map(({ icon: Icon, title, desc }) => (
                <li key={title} className="flex flex-col gap-3">
                  {/* 명세 §4.4: 44px × 44px, border-radius 11px, bg #E8F1FE (blue-bg) */}
                  <div className="grid size-11 shrink-0 place-items-center rounded-[11px] bg-[color:var(--color-brand-blue)]/10">
                    <Icon className="size-5 text-[color:var(--color-brand-blue)]" aria-hidden />
                  </div>
                  <h3 className="text-[17px] font-bold text-foreground">{title}</h3>
                  <p className="text-[13.5px] leading-relaxed text-muted-foreground">{desc}</p>
                </li>
              ))}
            </ul>
          </div>
        </section>

        {/* ── CTA 배너 ── */}
        <section className="bg-muted/40 px-6 py-14 md:px-20">
          <div className="mx-auto max-w-3xl text-center">
            <h2 className="text-2xl font-extrabold tracking-tight text-foreground">
              지금 무료로 시작하세요
            </h2>
            <p className="mt-3 text-muted-foreground">
              가입 1분이면 첫 공시 해석을 받아볼 수 있습니다.
              카카오·구글로 즉시 시작하거나 이메일로 가입하세요.
            </p>
            <Link
              href="/signup"
              className={buttonVariants({ size: "lg" }) + " mt-6 px-8"}
            >
              3개 종목 무료로 시작하기 →
            </Link>
          </div>
        </section>

        {/* ── 면책 고지 ── */}
        <footer className="border-t border-border px-6 py-8 md:px-20">
          <div className="mx-auto max-w-7xl">
            <DisclaimerNotice className="border-0 bg-transparent px-0 text-[11px]" />
          </div>
        </footer>
      </main>
    </>
  );
}
