"use client";

// [목적] 내 포트폴리오 대시보드 — 총 평가금액·평가 손익(KRX 종가 기반)·보유 종목 테이블·종목별 최근 공시 표시
// [이유] /portfolios 를 단순 종목 등록 페이지에서 대시보드로 개편 (종목 등록은 /portfolios/new 로 이동).
//   상단 카드는 GET /portfolios/summary 서버 집계(대시보드와 동일 소스), 테이블 행은 목록 응답의
//   close_price(공개 시세)로 클라이언트 계산 — 별도 API 추가 없이 기존 응답 재사용.
// [사이드 임팩트] usePortfolios·usePortfolioSummary·useDisclosures(scope:"portfolio")·usePricingPlans·useTodaySeoul 사용.
//   행 정렬 = 평가손익 내림차순(계산 불가 행은 뒤로) — 헤더 "평가손익순" 라벨과 정합.
//   종목별 최근 공시 쿼리는 최근 N일(recentDays, Asia/Seoul 오늘 포함) from/to 필터 — 패널과 테이블 "최근 공시" 배지가
//   같은 응답을 공유하므로 배지도 같은 창 기준(창 밖이면 "—"). Free 티어는 BE가 동일 창 + 5건으로 클램프.
//   공시 상대시간은 공용 formatDisclosureDate(대시보드·공시 피드와 표기 통일). "이번 주 공시" 감성 카운트는
//   색+단축 라벨(호/중/악) 병기 — 색 단독 금지(§6-5), 화살표 대신 문자로 등락 오독 방지(§11.1).
// [수정 시 고려사항] close_price는 KrxPriceSyncJob 일배치(18:00 KST) 기준 — 미수집(null) 시 해당 셀 "—" 폴백.
//   "이번 주 공시"의 weekNeutral은 보류(is_withheld)를 포함(집계 정의) — aria-label에 "중립·보류"로 명시.
//   수익률/평가금액은 avg_buy_price·quantity가 null이면 계산 불가 → "—" (엣지: 선택 입력 항목).
//   매수가·수량 console.log 절대 금지 (금융 개인정보, CLAUDE.md §7). 손익은 사실 표시만 — 투자 권유 표현 금지(§11.1).
//   종목별 최근 공시 창(recentDays)은 BE /pricing/plans FREE recent_window_days에서 파생(단일 소스).
//   API 로드 전에는 FREE_RECENT_WINDOW_DAYS 폴백. 라벨("최근 N일")·빈 상태 문구도 이 값으로 파생 — 라벨-데이터 자동 정합.
//   getWeekRange()는 로컬 TZ 기준(이번 주 카드 전용) — 최근 N일 계산에 복사 금지, shiftDateStr+useTodaySeoul 사용.

import { useMemo } from "react";
import Link from "next/link";
import { usePortfolios, usePortfolioSummary, type Portfolio } from "@/lib/api/portfolios";
import { useDisclosures, type Sentiment } from "@/lib/api/disclosures";
import { usePricingPlans } from "@/lib/api/pricing";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { useTodaySeoul } from "@/lib/hooks/useTodaySeoul";
import { shiftDateStr } from "@/lib/date/shiftDateStr";
import { formatDisclosureDate } from "@/lib/date/formatDisclosureDate";
import { FREE_RECENT_WINDOW_DAYS } from "@/lib/config/tierWindow";
import { StatCard, PnlStatCard, formatKrwCompact } from "@/components/domain/StatCards";
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

/** 행 단위 평가 지표 — close_price(공개 시세) × 사용자 입력값으로 클라이언트 계산. null = 계산 불가("—" 표시). */
function computeRowEval(p: Portfolio): { evalAmount: number | null; pnl: number | null; pnlRate: number | null } {
  const { close_price, avg_buy_price, quantity } = p;
  const evalAmount = close_price != null && quantity != null ? close_price * quantity : null;
  const pnl =
    close_price != null && avg_buy_price != null && quantity != null
      ? (close_price - avg_buy_price) * quantity
      : null;
  const pnlRate =
    close_price != null && avg_buy_price != null && avg_buy_price > 0
      ? ((close_price - avg_buy_price) / avg_buy_price) * 100
      : null;
  return { evalAmount, pnl, pnlRate };
}

export default function PortfoliosDashboardPage() {
  const { data: portfolios, isLoading: portfoliosLoading } = usePortfolios();
  const { data: summary } = usePortfolioSummary();
  const { from, to } = useMemo(() => getWeekRange(), []);
  // 최근 공시 창 = BE FREE plan recent_window_days(단일 소스). API 로드 전엔 폴백 상수.
  const { data: pricingPlans } = usePricingPlans();
  const recentDays =
    pricingPlans?.find((p) => p.tier === "FREE")?.recent_window_days || FREE_RECENT_WINDOW_DAYS;
  // useTodaySeoul 파생이라 자정 경과 시 자동 갱신(쿼리 키 변경 → 리페치)
  const today = useTodaySeoul();
  const recentFrom = useMemo(() => shiftDateStr(today, -(recentDays - 1)), [today, recentDays]);

  const { data: allPortfolioDisclosures, isLoading: disclosuresLoading } = useDisclosures({
    scope: "portfolio",
    from: recentFrom,
    to: today,
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

  // 행 평가 지표 1회 계산 + 평가손익 내림차순 정렬 — 헤더 "평가손익순" 라벨과 정합.
  // pnl 계산 불가(수량 미입력 등) 행은 뒤로 보내되, 그 안에서는 pnlRate 내림차순 보조 정렬(수익률만 보이는 행 배려).
  const sortedRows = useMemo(() => {
    if (!portfolios?.length) return [];
    return portfolios
      .map((p) => ({ p, ev: computeRowEval(p) }))
      .sort((a, b) => {
        if (a.ev.pnl !== null && b.ev.pnl !== null) return b.ev.pnl - a.ev.pnl;
        if (a.ev.pnl !== null) return -1;
        if (b.ev.pnl !== null) return 1;
        return (b.ev.pnlRate ?? -Infinity) - (a.ev.pnlRate ?? -Infinity);
      });
  }, [portfolios]);

  // 총 평가금액 카드 — 서버 집계(summary). priced_count 0이면 미수집 상태로 placeholder 유지.
  const hasEval = (summary?.priced_count ?? 0) > 0;
  const evalCompact = hasEval ? formatKrwCompact(summary!.total_eval_amount) : null;
  const evalNote = hasEval
    ? `${summary!.as_of} 종가 기준${summary!.unpriced_count > 0 ? ` · ${summary!.unpriced_count}개 미수집` : ""}`
    : "종가 수집 중";

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
        {evalCompact ? (
          <StatCard label="총 평가금액" value={evalCompact.value} unit={evalCompact.unit} note={evalNote} />
        ) : (
          <StatCard label="총 평가금액" value="—" note={evalNote} muted />
        )}
        <PnlStatCard
          pnl={hasEval ? summary!.total_pnl : null}
          pnlRate={summary?.pnl_rate ?? null}
          asOf={summary?.as_of ?? null}
          unpricedCount={summary?.unpriced_count ?? 0}
        />
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
              // 색 단독 금지(§6-5) — 색 + 단축 라벨(호/중/악) 병기 + 그룹 aria-label로 색맹·시니어 배려.
              // 라벨은 화살표(▲/▼) 대신 문자 사용: 투자 앱에서 등락으로 오독 방지(§11.1). "중"에는 보류 포함(집계 정의).
              <span
                className="ml-0.5 flex items-baseline gap-1.5 text-sm font-bold"
                aria-label={`호재 ${weekPositive}건, 중립·보류 ${weekNeutral}건, 악재 ${weekNegative}건`}
              >
                <span aria-hidden className="text-muted-foreground">·</span>
                {[
                  { label: "호", value: weekPositive, tone: "text-[color:var(--color-sentiment-positive)]" },
                  { label: "중", value: weekNeutral, tone: "text-[color:var(--color-sentiment-neutral)]" },
                  { label: "악", value: weekNegative, tone: "text-[color:var(--color-sentiment-negative)]" },
                ].map(({ label, value, tone }) => (
                  <span key={label} className={tone}>
                    <span aria-hidden className="text-[11px] font-semibold">{label}</span>
                    {value}
                  </span>
                ))}
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
                {sortedRows.map(({ p, ev: { evalAmount, pnlRate } }) => {
                  const latest = latestByStock.get(p.stock_code);
                  const isProfit = pnlRate !== null && pnlRate > 0;
                  const isLoss = pnlRate !== null && pnlRate < 0;
                  const rateColorClass = isProfit
                    ? "text-[color:var(--color-sentiment-positive)]"
                    : isLoss
                      ? "text-[color:var(--color-sentiment-negative)]"
                      : "text-foreground";
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
                      {/* 현재가 — KRX 일배치 종가. 미수집 시 "—" */}
                      <p className="hidden text-right tabular-nums text-sm text-foreground sm:block">
                        {p.close_price != null ? p.close_price.toLocaleString() : <span className="text-muted-foreground">—</span>}
                      </p>
                      {/* 수익률 — 색 단독 금지: 색 + 부호/아이콘 병기 (WCAG §6-5) */}
                      <p className={`hidden text-right text-sm font-semibold sm:block ${rateColorClass}`}>
                        {pnlRate !== null ? (
                          <>
                            {isProfit && <span aria-hidden>▲</span>}
                            {isLoss && <span aria-hidden>▼</span>}
                            {pnlRate > 0 ? "+" : ""}
                            {pnlRate.toFixed(2)}%
                          </>
                        ) : (
                          <span className="font-normal text-muted-foreground">—</span>
                        )}
                      </p>
                      {/* 평가금액 = 종가 × 수량. 수량 미입력/종가 미수집 시 "—" */}
                      <p className="hidden text-right tabular-nums text-sm text-foreground sm:block">
                        {evalAmount !== null ? Math.round(evalAmount).toLocaleString() : <span className="text-muted-foreground">—</span>}
                      </p>
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
              <p className="font-extrabold text-foreground">
                🔔 종목별 최근 공시{" "}
                <span className="text-xs font-semibold text-muted-foreground">최근 {recentDays}일</span>
              </p>
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
                <p className="px-5 py-6 text-center text-sm text-muted-foreground">
                  최근 {recentDays}일 내 공시가 없습니다.
                </p>
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
                        {formatDisclosureDate(d.rcept_dt)}
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
