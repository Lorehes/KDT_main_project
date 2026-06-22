// [목적] 서비스 랜딩 페이지(D1/m01) — 가치 제안·CTA·기능 소개·면책 고지
// [이유] 비로그인 방문자에게 공시레이더의 핵심 가치를 소개하고 가입·요금제로 유도하는 마케팅 LP
// [사이드 임팩트] PublicNavbar는 (public) 그룹 layout이 아닌 직접 import — 루트 page.tsx는 (public) 그룹 밖이기 때문
// [수정 시 고려사항] 로그인 세션 있으면 middleware.ts에서 SSR 리다이렉트(/dashboard). 클라이언트 fetchMe 불필요.
//   히어로 우측 목업(MOCK_DISCLOSURES)은 익명 허구 데이터 — 자본시장법 §11.1 면책 배지 포함.
//   실제 공시 데이터로 교체 시 BE API 연동 + async 서버 컴포넌트 전환 필요.
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
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import type { Sentiment } from "@/lib/api/disclosures";

export const metadata: Metadata = {
  title: "공시레이더 — DART 공시를 호재·악재로 즉시 해석",
  description:
    "보유 종목의 DART 공시를 AI가 호재·중립·악재로 판별해 카카오 알림톡과 웹 대시보드로 알려드립니다. 무료로 시작하세요.",
};

// [목적] LP 히어로 우측 시뮬레이션 목업 데이터 — 서비스 가치를 직관적으로 전달하는 데모 공시 카드
// [이유] 기존 opacity-40 placeholder는 서비스 가치 전달 실패 → 전환율 개선 목적으로 교체
// [사이드 임팩트] 정적 상수. BE 연동 없음. SentimentBadge 재사용만, 수정 없음
// [수정 시 고려사항] 반드시 익명 기업명·가상 티커 유지 (자본시장법 §11.1).
//   실제 데이터 교체 시 → async 서버 컴포넌트 전환 + BE GET /disclosures?scope=portfolio 연동
const MOCK_DISCLOSURES: ReadonlyArray<{
  company: string;
  ticker: string;
  title: string;
  sentiment: Sentiment;
  summary: string;
  time: string;
}> = [
  {
    company: "A전자",
    ticker: "00••••",
    title: "주요사항보고서(유상증자결정)",
    sentiment: "NEGATIVE",
    summary: "보통주 5% 규모 유상증자 결정으로 단기 희석 우려.",
    time: "방금 전",
  },
  {
    company: "B반도체",
    ticker: "00••••",
    title: "실적발표(2025 3Q)",
    sentiment: "POSITIVE",
    summary: "주력 제품 출하 증가로 영업이익 전분기 대비 42% 상승.",
    time: "3분 전",
  },
  {
    company: "C바이오",
    ticker: "00••••",
    title: "임원ㆍ주요주주특정증권등소유상황보고서",
    sentiment: "NEUTRAL",
    summary: "최대주주 지분 변동 없음, 내부 거래 예정 없음.",
    time: "8분 전",
  },
];

// [목적] 히어로 목업 단일 공시 카드 — 회사명·배지·요약을 compact하게 표시
// [이유] page.tsx 로컬 함수로 정의. LP 전용 비주얼이라 공유 컴포넌트 분리 불필요 (dc-tech-review 결정)
// [사이드 임팩트] SentimentBadge 재사용. SentimentBadge 시그니처 변경 시 sentiment prop 영향
// [수정 시 고려사항] animate-in / motion-reduce:animate-none 쌍 유지. index 기반 stagger delay는 카드 수 변경 시 자동 적용
function HeroMockupCard({
  company,
  ticker,
  title,
  sentiment,
  summary,
  time,
  index,
}: {
  company: string;
  ticker: string;
  title: string;
  sentiment: Sentiment;
  summary: string;
  time: string;
  index: number;
}) {
  return (
    <div
      className="animate-in fade-in slide-in-from-bottom-3 duration-500 motion-reduce:animate-none rounded-xl border border-white/20 bg-white/[0.15] px-4 py-3"
      style={{ animationDelay: `${index * 150}ms`, animationFillMode: "both" }}
    >
      <div className="mb-1.5 flex items-center justify-between gap-2">
        <span className="text-[13px] font-bold text-white">
          {company}
          <span className="ml-1.5 text-[11px] font-normal text-blue-300/60">{ticker}</span>
        </span>
        <span className="shrink-0 text-[11px] text-blue-300/50">{time}</span>
      </div>
      <p className="mb-2 truncate text-[12px] text-blue-200/70">{title}</p>
      <div className="flex items-start gap-2">
        <SentimentBadge sentiment={sentiment} size="sm" />
        <p className="line-clamp-2 text-[12px] leading-relaxed text-blue-100/80">{summary}</p>
      </div>
    </div>
  );
}

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
                  href="/dashboard/preview"
                  className={
                    buttonVariants({ variant: "ghost", size: "lg" }) +
                    " border border-white/20 px-7 text-white hover:bg-white/10 hover:text-white"
                  }
                >
                  대시보드 둘러보기
                </Link>
              </div>
            </div>

            {/* 히어로 시뮬레이션 목업 — aria-hidden: 장식 콘텐츠, 좌측 텍스트로 충분히 전달됨 */}
            <div
              className="hidden min-h-[380px] flex-col gap-3 rounded-2xl border border-white/10 bg-white/5 p-4 md:flex"
              aria-hidden="true"
            >
              {/* 면책 배지 — 자본시장법 §11.1 */}
              <div className="flex items-center gap-1.5 rounded-lg bg-white/[0.08] px-3 py-1.5">
                <span className="size-1.5 rounded-full bg-blue-300" />
                <span className="text-[11px] font-medium text-blue-200">
                  데모 시뮬레이션 — 실제 데이터 아님
                </span>
              </div>
              {MOCK_DISCLOSURES.map((d, i) => (
                <HeroMockupCard key={d.company} {...d} index={i} />
              ))}
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
                  <p className="break-keep text-[13.5px] leading-relaxed text-muted-foreground">{desc}</p>
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
