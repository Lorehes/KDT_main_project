"use client";

// [목적] 대시보드(D2/m03) — 통계 카드 + 공시 피드. 종목 0건 시 Empty state(D11/m15) 표시
// [이유] 앱 홈으로 매일 방문하는 핵심 화면. 빈 상태는 종목 등록으로 즉시 유도
// [사이드 임팩트] useDisclosures(scope=portfolio)·usePortfolios·useAuthStore 의존.
//   W4에서 DisclosureCard 피드를 실제 데이터로 교체 예정 — 현재는 구조 + empty state 중심 구현
// [수정 시 고려사항] 통계 카드 수치는 W4에서 /disclosures 응답 집계로 교체.
//   모바일에서 통계 카드는 2열 grid. 공시 피드는 무한 스크롤 예정(W4)

import Link from "next/link";
import { Briefcase, Bell, TrendingUp } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { useAuthStore } from "@/lib/stores/authStore";
import { usePortfolios } from "@/lib/api/portfolios";
import { useDisclosures } from "@/lib/api/disclosures";
import { DisclosureCard } from "@/components/domain/DisclosureCard";

export default function DashboardPage() {
  const { user } = useAuthStore();
  const { data: portfolios } = usePortfolios();
  const { data: disclosurePage, isLoading } = useDisclosures({ scope: "portfolio", size: 10 });

  const hasPortfolios = (portfolios?.length ?? 0) > 0;
  const disclosures = disclosurePage?.content ?? [];
  const nickname = user?.nickname ?? "투자자";

  return (
    <div className="flex flex-col gap-6">
      {/* 헤더 */}
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs font-extrabold uppercase tracking-widest text-primary">Good morning</p>
          <h1 className="mt-1 text-2xl font-extrabold tracking-tight text-foreground">
            오늘의 내 공시 레이더, {nickname}님
          </h1>
        </div>
        <Link href="/portfolios/new" className={buttonVariants({ size: "sm" }) + " shrink-0"} aria-label="종목 등록">
          ＋ 종목
        </Link>
      </div>

      {/* 통계 카드 4종 — W4에서 실제 데이터로 교체 */}
      <ul className="grid grid-cols-2 gap-4 lg:grid-cols-4" aria-label="오늘 공시 통계">
        {[
          { label: "오늘 공시", value: disclosures.length, unit: "건" },
          { label: "호재", value: disclosures.filter((d) => d.sentiment === "POSITIVE").length, unit: "건" },
          { label: "악재", value: disclosures.filter((d) => d.sentiment === "NEGATIVE").length, unit: "건" },
          { label: "보유 종목", value: portfolios?.length ?? 0, unit: "개" },
        ].map(({ label, value, unit }) => (
          <li key={label} className="rounded-2xl border border-border bg-card p-5 shadow-sm">
            <p className="text-xs font-semibold text-muted-foreground">{label}</p>
            <p className="mt-2.5 text-3xl font-extrabold leading-none text-foreground">
              {value}
              <small className="ml-1 text-sm font-semibold text-muted-foreground">{unit}</small>
            </p>
          </li>
        ))}
      </ul>

      {/* 공시 피드 or Empty state */}
      {!hasPortfolios ? (
        <EmptyState />
      ) : isLoading ? (
        <div className="flex justify-center py-12 text-sm text-muted-foreground" role="status" aria-live="polite">
          공시를 불러오는 중...
        </div>
      ) : disclosures.length === 0 ? (
        <div className="rounded-2xl border border-border bg-card p-10 text-center text-sm text-muted-foreground">
          오늘 등록 종목의 신규 공시가 없습니다.
        </div>
      ) : (
        <section aria-label="보유 종목 공시 피드">
          <div className="mb-4 flex items-center justify-between">
            <h2 className="text-base font-extrabold text-foreground">최신 공시</h2>
            <Link href="/disclosures" className="text-sm font-bold text-primary hover:underline">전체 보기</Link>
          </div>
          <ul className="flex flex-col gap-3">
            {disclosures.map((d) => (
              <li key={d.id}>
                <DisclosureCard disclosure={d} />
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}

function EmptyState() {
  return (
    <div className="grid gap-5 md:grid-cols-[1.4fr_1fr]">
      {/* 엠티 안내 */}
      <div className="flex flex-col items-center justify-center gap-4 rounded-2xl border border-border bg-card p-10 text-center">
        <div className="grid size-[84px] place-items-center rounded-[22px] bg-primary/10" aria-hidden>
          <Briefcase className="size-10 text-primary" />
        </div>
        <div>
          <h2 className="text-xl font-extrabold text-foreground">아직 등록된 종목이 없어요</h2>
          <p className="mt-2 max-w-[38ch] text-sm text-muted-foreground">
            보유 종목을 등록하면 해당 공시가 뜰 때 호재·악재를 해석해 알려드려요.
          </p>
        </div>
        <div className="flex gap-2.5">
          <Link href="/portfolios/new" className={buttonVariants()}>＋ 보유 종목 등록</Link>
        </div>
      </div>

      {/* 추천 종목 카드 */}
      <div className="rounded-2xl border border-border bg-card p-5">
        <p className="mb-4 text-sm font-extrabold text-foreground">이런 종목은 어때요?</p>
        <ul className="flex flex-col gap-3">
          {[
            { code: "005930", name: "삼성전자", market: "코스피", color: "#1428A0", abbr: "SE" },
            { code: "035420", name: "NAVER",   market: "코스피", color: "#03C75A", abbr: "NV" },
            { code: "000660", name: "SK하이닉스", market: "코스피", color: "#E2231A", abbr: "SK" },
          ].map(({ code, name, market, color, abbr }) => (
            <li key={code} className="flex items-center justify-between rounded-xl border border-border bg-background px-3.5 py-3">
              <div className="flex items-center gap-3">
                <div className="grid size-8 place-items-center rounded-lg font-extrabold text-xs text-white" style={{ background: color }} aria-hidden>{abbr}</div>
                <div>
                  <p className="text-sm font-bold text-foreground">{name}</p>
                  <p className="font-mono text-xs text-muted-foreground">{code} · {market}</p>
                </div>
              </div>
              <Link href={`/portfolios/new?code=${code}`} className={buttonVariants({ variant: "outline", size: "sm" })}>＋ 추가</Link>
            </li>
          ))}
        </ul>
        <p className="mt-3 text-[11px] text-muted-foreground">* 추천은 인기 종목 기준 정적 표시입니다.</p>
      </div>

      {/* 알림 설정 유도 배너 */}
      <div className="flex items-center gap-4 rounded-2xl bg-[color:var(--color-brand-navy)] p-5 text-white md:col-span-2">
        <Bell className="size-6 shrink-0 text-[color:var(--color-brand-sky)]" aria-hidden />
        <div className="flex-1">
          <p className="text-sm font-extrabold">카카오 알림톡 설정하기</p>
          <p className="mt-0.5 text-xs text-blue-200">공시가 나오면 카카오톡으로 즉시 알려드려요.</p>
        </div>
        <Link href="/notifications/settings" className={buttonVariants({ size: "sm" }) + " shrink-0 bg-white text-[color:var(--color-brand-navy)] hover:bg-white/90"}>
          설정
        </Link>
      </div>
    </div>
  );
}
