"use client";

// [목적] 내 포트폴리오 대시보드 — 총 평가금액·보유 종목 테이블·종목별 최근 공시 한눈에 표시
// [이유] /portfolios 를 단순 종목 등록 페이지에서 대시보드로 개편 (종목 등록은 /portfolios/new 로 이동)
// [사이드 임팩트] usePortfolios·useDisclosures(scope:"portfolio") 사용.
//   평가금액·손익·현재가는 시세 연동 전 "—" 표시 (StatCard muted 옵션).
// [수정 시 고려사항] 시세 API 연동 시 avg_buy_price·quantity 기반 손익 계산 추가 필요.
//   매수가·수량 console.log 절대 금지 (금융 개인정보, CLAUDE.md §7).

import { useMemo } from "react";
import Link from "next/link";
import { usePortfolios } from "@/lib/api/portfolios";
import { useDisclosures, type Sentiment } from "@/lib/api/disclosures";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { StatCard } from "@/components/domain/StatCards";
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import { Skeleton } from "@/components/ui/skeleton";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";

function getWeekRange() {
  const now = new Date();
  const day = now.getDay();
  const monday = new Date(now);
  monday.setDate(now.getDate() - (day === 0 ? 6 : day - 1));
  monday.setHours(0, 0, 0, 0);
  const sunday = new Date(monday);
  sunday.setDate(monday.getDate() + 6);
  sunday.setHours(23, 59, 59, 999);
  const fmt = (d: Date) => d.toISOString().slice(0, 10);
  return { from: fmt(monday), to: fmt(sunday) };
}

function formatRelativeTime(dateStr: string): string {
  // DART rcept_dt 형식: "20260622" (YYYYMMDD) 또는 ISO 문자열 처리
  const normalized = /^\d{8}$/.test(dateStr)
    ? `${dateStr.slice(0, 4)}-${dateStr.slice(4, 6)}-${dateStr.slice(6, 8)}`
    : dateStr;
  const diffMs = Date.now() - new Date(normalized).getTime();
  const min = Math.floor(diffMs / 60_000);
  if (min < 1) return "방금 전";
  if (min < 60) return `${min}분 전`;
  const hr = Math.floor(min / 60);
  if (hr < 24) return `${hr}시간 전`;
  return `${Math.floor(hr / 24)}일 전`;
}

export default function PortfoliosDashboardPage() {
  const { data: portfolios, isLoading: portfoliosLoading } = usePortfolios();
  const { from, to } = useMemo(() => getWeekRange(), []);

  const { data: allPortfolioDisclosures, isLoading: disclosuresLoading } = useDisclosures({
    scope: "portfolio",
    size: 50,
    sort: "rcept_dt,desc",
  });
  const { data: weekDisclosures } = useDisclosures({
    scope: "portfolio",
    from,
    to,
    size: 100,
  });

  const showSkeleton = useDelayedLoading(portfoliosLoading);

  const stockCount = portfolios?.length ?? 0;
  const recentList = allPortfolioDisclosures?.content ?? [];

  // 이번 주 공시 감성별 집계
  const weekList = weekDisclosures?.content ?? [];
  const weekTotal = weekList.length;
  const weekPositive = weekList.filter((d) => !d.is_withheld && d.sentiment === "POSITIVE").length;
  const weekNeutral = weekList.filter((d) => d.is_withheld || d.sentiment === "NEUTRAL").length;
  const weekNegative = weekList.filter((d) => !d.is_withheld && d.sentiment === "NEGATIVE").length;

  // 종목별 최신 공시 lookup (테이블 최근 공시 badge용)
  const latestByStock = useMemo(() => {
    const map = new Map<string, { sentiment?: Sentiment; is_withheld?: boolean }>();
    for (const d of recentList) {
      if (!map.has(d.stock_code)) {
        map.set(d.stock_code, { sentiment: d.sentiment, is_withheld: d.is_withheld });
      }
    }
    return map;
  }, [recentList]);

  return (
    <div className="flex flex-col gap-6">
      {/* 헤더 */}
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-xs font-extrabold uppercase tracking-widest text-primary">My Portfolio</p>
          <h1 className="mt-1 text-2xl font-extrabold tracking-tight text-foreground">내 포트폴리오</h1>
        </div>
        <Link href="/portfolios/new" className={cn(buttonVariants(), "shrink-0")}>
          + 종목 추가
        </Link>
      </div>

      {/* 통계 카드 */}
      <ul className="m-0 grid list-none gap-4 p-0 grid-cols-2 lg:grid-cols-4">
        <StatCard label="총 평가금액" value="—" note="시세 연동 준비 중" muted />
        <StatCard label="평가 손익" value="—" note="시세 연동 준비 중" muted />
        <li className="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <p className="text-xs font-semibold text-muted-foreground">보유 종목</p>
          {showSkeleton ? (
            <Skeleton className="mt-2.5 h-8 w-14" />
          ) : (
            <p className="mt-2.5 text-3xl font-extrabold leading-none text-foreground">
              {stockCount}
              <small className="ml-1 text-sm font-semibold text-muted-foreground">종목</small>
            </p>
          )}
        </li>
        <li className="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <p className="text-xs font-semibold text-muted-foreground">이번 주 공시</p>
          <div className="mt-2.5 flex items-baseline gap-1 leading-none">
            <span className="text-3xl font-extrabold text-foreground">{weekTotal}</span>
            <span className="text-sm font-semibold text-muted-foreground">건</span>
            {weekTotal > 0 && (
              <span className="ml-0.5 text-sm font-bold">
                ·{" "}
                <span className="text-[color:var(--color-sentiment-positive)]">{weekPositive}</span>
                <span className="text-muted-foreground">/{weekNeutral}/</span>
                <span className="text-[color:var(--color-sentiment-negative)]">{weekNegative}</span>
              </span>
            )}
          </div>
        </li>
      </ul>

      {/* 메인 콘텐츠 */}
      <div className="grid gap-5 lg:grid-cols-[1.4fr_1fr] lg:items-start">
        {/* 좌측: 보유 종목 테이블 */}
        <div className="rounded-2xl border border-border bg-card shadow-sm">
          <div className="flex items-center justify-between border-b border-border px-5 py-4">
            <p className="font-extrabold text-foreground">💼 보유 종목</p>
            <span className="text-xs text-muted-foreground">평가손익순</span>
          </div>

          {showSkeleton ? (
            <div className="divide-y divide-border">
              {[...Array(3)].map((_, i) => (
                <div key={i} className="flex items-center gap-3 px-5 py-4">
                  <Skeleton className="size-9 shrink-0 rounded-lg" />
                  <div className="flex flex-1 flex-col gap-1.5">
                    <Skeleton className="h-4 w-24" />
                    <Skeleton className="h-3 w-14" />
                  </div>
                  <Skeleton className="h-4 w-16" />
                </div>
              ))}
            </div>
          ) : !portfolios?.length ? (
            <div className="flex flex-col items-center gap-3 py-12 text-center">
              <p className="text-sm text-muted-foreground">아직 등록된 종목이 없습니다.</p>
              <Link href="/portfolios/new" className={buttonVariants({ size: "sm" })}>
                종목 추가하기
              </Link>
            </div>
          ) : (
            <>
              {/* 테이블 헤더 */}
              <div className="hidden grid-cols-[2fr_1.1fr_1fr_1fr_1.2fr_1fr] gap-2 border-b border-border px-5 py-2.5 text-xs text-muted-foreground sm:grid">
                <span>종목</span>
                <span className="text-right">평단·수량</span>
                <span className="text-right">현재가</span>
                <span className="text-right">수익률</span>
                <span className="text-right">평가금액</span>
                <span className="text-right">최근 공시</span>
              </div>
              <div className="divide-y divide-border">
                {portfolios.map((p) => {
                  const latest = latestByStock.get(p.stock_code);
                  return (
                    <div
                      key={p.id}
                      className="grid grid-cols-[2fr_auto] items-center gap-2 px-5 py-4 sm:grid-cols-[2fr_1.1fr_1fr_1fr_1.2fr_1fr]"
                    >
                      {/* 종목 */}
                      <div className="flex items-center gap-2.5">
                        <div
                          className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground"
                          aria-hidden
                        >
                          {(p.corp_name ?? p.stock_code).slice(0, 2)}
                        </div>
                        <div className="min-w-0">
                          <p className="truncate text-sm font-bold text-foreground">{p.corp_name ?? p.stock_code}</p>
                          <p className="font-mono text-xs text-muted-foreground">{p.stock_code}</p>
                        </div>
                      </div>
                      {/* 평단·수량 */}
                      <div className="text-right">
                        <p className="tabular-nums text-sm font-semibold text-foreground">
                          {p.avg_buy_price?.toLocaleString() ?? "—"}
                        </p>
                        <p className="text-xs text-muted-foreground">
                          {p.quantity != null ? `${p.quantity}주` : "—"}
                        </p>
                      </div>
                      {/* 현재가 (시세 연동 전 placeholder) */}
                      <p className="hidden text-right tabular-nums text-sm text-muted-foreground sm:block">—</p>
                      {/* 수익률 (시세 연동 전 placeholder) */}
                      <p className="hidden text-right text-sm text-muted-foreground sm:block">—</p>
                      {/* 평가금액 (시세 연동 전 placeholder) */}
                      <p className="hidden text-right tabular-nums text-sm text-muted-foreground sm:block">—</p>
                      {/* 최근 공시 badge */}
                      <div className="hidden justify-end sm:flex">
                        {latest?.sentiment ? (
                          <SentimentBadge
                            sentiment={latest.sentiment}
                            isWithheld={latest.is_withheld}
                            size="sm"
                          />
                        ) : (
                          <span className="text-sm text-muted-foreground">—</span>
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
              <p className="border-t border-border px-5 py-3 text-xs text-muted-foreground">
                평가금액·손익은 지연 시세 기준 참고입니다. 본 정보는 투자 권유가 아닙니다.
              </p>
            </>
          )}
        </div>

        {/* 우측 패널 */}
        <div className="flex flex-col gap-4">
          {/* 종목별 최근 공시 */}
          <div className="rounded-2xl border border-border bg-card shadow-sm">
            <div className="flex items-center justify-between border-b border-border px-5 py-4">
              <p className="font-extrabold text-foreground">🔔 종목별 최근 공시</p>
              <Link
                href="/disclosures?scope=portfolio"
                className="text-xs font-semibold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                전체
              </Link>
            </div>
            <div className="divide-y divide-border">
              {disclosuresLoading ? (
                [...Array(3)].map((_, i) => (
                  <div key={i} className="flex items-center justify-between gap-3 px-5 py-3.5">
                    <div className="flex flex-1 flex-col gap-1.5">
                      <Skeleton className="h-4 w-20" />
                      <Skeleton className="h-3 w-32" />
                    </div>
                    <Skeleton className="h-6 w-12 rounded-full" />
                  </div>
                ))
              ) : !recentList.length ? (
                <p className="px-5 py-6 text-center text-sm text-muted-foreground">아직 공시가 없습니다.</p>
              ) : (
                recentList.slice(0, 5).map((d) => (
                  <Link
                    key={d.id}
                    href={`/disclosures/${d.id}`}
                    className="flex items-start justify-between gap-3 px-5 py-3.5 transition-colors hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-inset focus-visible:ring-1 focus-visible:ring-ring"
                  >
                    <div className="min-w-0 flex-1">
                      <p className="truncate text-sm font-bold text-foreground">{d.corp_name}</p>
                      <p className="truncate text-xs text-muted-foreground">{d.report_nm}</p>
                    </div>
                    <div className="flex shrink-0 flex-col items-end gap-1">
                      {d.sentiment && (
                        <SentimentBadge
                          sentiment={d.sentiment}
                          isWithheld={d.is_withheld}
                          size="sm"
                        />
                      )}
                      <span className="whitespace-nowrap text-xs text-muted-foreground">
                        {formatRelativeTime(d.rcept_dt)}
                      </span>
                    </div>
                  </Link>
                ))
              )}
            </div>
          </div>

          {/* 새 종목 추가 CTA */}
          <div className="flex items-center justify-between rounded-2xl bg-foreground px-5 py-4">
            <div className="flex items-center gap-3">
              <div className="grid size-9 shrink-0 place-items-center rounded-full border border-background/30 text-xl font-bold text-background">
                +
              </div>
              <div>
                <p className="font-extrabold text-background">새 종목 추가</p>
                <p className="text-xs text-background/70">Pro는 무제한으로 종목을 담을 수 있어요.</p>
              </div>
            </div>
            <Link
              href="/portfolios/new"
              aria-label="종목 추가하기"
              className={buttonVariants({ size: "sm", variant: "secondary" })}
            >
              +
            </Link>
          </div>
        </div>
      </div>
    </div>
  );
}
