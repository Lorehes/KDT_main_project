"use client";

// [목적] 대시보드(D2/m03) — 오늘의 보유 종목 공시 레이더: 통계 카드 + 공시 피드. 종목 0건 시 Empty state(D11/m15) 표시
// [이유] 앱 홈으로 매일 방문하는 핵심 화면. 빈 상태는 종목 등록으로 즉시 유도
// [사이드 임팩트] useDisclosures(scope=portfolio, from/to=오늘 Asia/Seoul)·usePortfolios·useAuthStore·useUIStore 의존.
//   Free 티어는 BE가 오늘+page0+5건 강제(dashboard-real-data R3). total_elements>5 시 업그레이드 배너 표시(R4).
// [수정 시 고려사항] Free 제한 배너 문구는 자본시장법 §11.1 — 투자 권유 표현 금지, 기능 안내로 한정.
//   평가 손익(StatCard)은 KRX 현재가 연동 전까지 placeholder. 모바일에서 통계 카드는 2열 grid.

import Link from "next/link";
import { Briefcase, Bell } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import { useAuthStore } from "@/lib/stores/authStore";
import { useUIStore } from "@/lib/stores/uiStore";
import { usePortfolios } from "@/lib/api/portfolios";
import { useDisclosures } from "@/lib/api/disclosures";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { DisclosureCard } from "@/components/domain/DisclosureCard";
import { Skeleton } from "@/components/ui/skeleton";
import { StatCard, SentimentStatCard } from "@/components/domain/StatCards";

export default function DashboardPage() {
  const { user } = useAuthStore();
  const { setUpsellModalOpen } = useUIStore();
  const { data: portfolios } = usePortfolios();
  // "sv" locale → YYYY-MM-DD, Asia/Seoul 기준 오늘 날짜 — BE Free 강제와 동일 기준
  const today = new Intl.DateTimeFormat("sv", { timeZone: "Asia/Seoul" }).format(new Date());
  const { data: disclosurePage, isLoading } = useDisclosures({ scope: "portfolio", size: 10, from: today, to: today });
  const showSkeleton = useDelayedLoading(isLoading);

  const hasPortfolios = (portfolios?.length ?? 0) > 0;
  const disclosures = disclosurePage?.content ?? [];
  const nickname = user?.nickname ?? "투자자";
  // total_elements는 BE가 size 클램핑 전 오늘 전체 카운트를 반환 → >5면 Free 제한 도달
  const isFreeLimited = user?.tier === "FREE" && (disclosurePage?.page.total_elements ?? 0) > 5;

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

      {/* 통계 카드 — 호재/악재/보류는 1개 카드로 통합 표기.
          평가 손익은 KRX 현재가 연동 전까지 placeholder("DB 연동 필요") */}
      <ul className="grid grid-cols-2 gap-4 lg:grid-cols-4" aria-label="오늘 공시 통계">
        <StatCard label="오늘 공시" value={disclosures.length} unit="건" />
        <SentimentStatCard
          positive={disclosures.filter((d) => !d.is_withheld && d.sentiment === "POSITIVE").length}
          neutral={disclosures.filter((d) => !d.is_withheld && d.sentiment === "NEUTRAL").length}
          negative={disclosures.filter((d) => !d.is_withheld && d.sentiment === "NEGATIVE").length}
          withheld={disclosures.filter((d) => d.is_withheld === true).length}
        />
        <StatCard label="보유 종목" value={portfolios?.length ?? 0} unit="개" />
        <StatCard label="평가 손익" value="—" muted note="DB 연동 필요" />
      </ul>

      {/* 공시 피드 or Empty state */}
      {!hasPortfolios ? (
        <EmptyState />
      ) : showSkeleton ? (
        <div className="flex flex-col gap-3" role="status" aria-label="공시 피드 불러오는 중">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="flex items-start gap-3 rounded-2xl border border-border bg-card p-4 shadow-sm">
              <Skeleton className="size-10 shrink-0 rounded-xl" />
              <div className="flex flex-1 flex-col gap-2">
                <div className="flex items-center gap-2">
                  <Skeleton className="h-4 w-20" />
                  <Skeleton className="h-5 w-12 rounded-full" />
                </div>
                <Skeleton className="h-4 w-full" />
                <Skeleton className="h-3 w-3/4" />
              </div>
            </div>
          ))}
        </div>
      ) : isLoading ? null : disclosures.length === 0 ? (
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
          {isFreeLimited && (
            <div
              className="mt-3 flex items-center justify-between gap-3 rounded-2xl border border-border bg-card px-4 py-3"
              role="status"
              aria-label="Free 플랜 일 5건 조회 완료 안내"
            >
              <p className="text-sm text-muted-foreground">
                오늘 5건 조회 완료 —{" "}
                <span className="font-bold text-foreground">Pro 플랜</span>에서 전체 공시를 확인할 수 있어요.
              </p>
              <Button size="sm" onClick={() => setUpsellModalOpen(true)} className="shrink-0" aria-label="Pro 플랜 업그레이드">
                Pro 보기
              </Button>
            </div>
          )}
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
