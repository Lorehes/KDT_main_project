"use client";

// [목적] 공시 피드 페이지(D15/m23) — 전체 공시를 필터·날짜 그룹으로 탐색
// [이유] 대시보드는 보유 종목 공시만 표시. 이 페이지는 전체 공시 탐색 + 감성 필터 제공
// [사이드 임팩트] useDisclosures 쿼리를 sentiment·scope 파라미터로 제어. TierGate는 Pro 미달 시 3개월 이력 차단
// [수정 시 고려사항] 날짜 그룹(오늘·어제·이번주)은 클라이언트에서 rcept_dt 파싱.
//   무한 스크롤은 useInfiniteQuery로 교체 예정(현재 페이지 기반). 필터 상태는 URL searchParams로 관리 권장

import { useState } from "react";
import { useDisclosures, type Sentiment, type Disclosure } from "@/lib/api/disclosures";
import { useAuthStore } from "@/lib/stores/authStore";
import { DisclosureCard } from "@/components/domain/DisclosureCard";
import { TierGate } from "@/components/domain/TierGate";
import { SentimentBadge } from "@/components/domain/SentimentBadge";

type FilterType = "ALL" | Sentiment;

const FILTERS: { value: FilterType; label: string }[] = [
  { value: "ALL",      label: "전체" },
  { value: "POSITIVE", label: "호재" },
  { value: "NEGATIVE", label: "악재" },
  { value: "NEUTRAL",  label: "중립" },
];

function groupByDate(disclosures: Disclosure[]) {
  const today = new Date().toISOString().slice(0, 10).replace(/-/g, "");
  const yesterday = new Date(Date.now() - 86400000).toISOString().slice(0, 10).replace(/-/g, "");

  const groups: Record<string, Disclosure[]> = {};
  disclosures.forEach((d) => {
    const label = d.rcept_dt === today ? "오늘" : d.rcept_dt === yesterday ? "어제" : d.rcept_dt;
    if (!groups[label]) groups[label] = [];
    groups[label].push(d);
  });
  return groups;
}

export default function DisclosuresFeedPage() {
  const { user } = useAuthStore();
  const [filter, setFilter] = useState<FilterType>("ALL");
  const isPro = user?.tier === "PRO" || user?.tier === "PREMIUM";

  const { data, isLoading, isError } = useDisclosures({
    scope: "portfolio",
    sentiment: filter === "ALL" ? undefined : filter,
    size: 30,
  });

  const disclosures = data?.content ?? [];
  const groups = groupByDate(disclosures);

  return (
    <div className="flex flex-col gap-6">
      {/* 헤더 */}
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight text-foreground">공시 피드</h1>
        <p className="mt-1 text-sm text-muted-foreground">보유 종목의 공시를 날짜별로 확인하세요.</p>
      </div>

      {/* 필터 칩 */}
      <div className="flex gap-2 overflow-x-auto pb-1" role="group" aria-label="공시 감성 필터">
        {FILTERS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            onClick={() => setFilter(value)}
            aria-pressed={filter === value}
            className={`shrink-0 rounded-full border px-4 py-2 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              filter === value
                ? "border-primary bg-primary text-primary-foreground"
                : "border-border bg-background text-muted-foreground hover:bg-muted"
            }`}
          >
            {value !== "ALL" ? (
              <span className="flex items-center gap-1.5">
                <SentimentBadge sentiment={value as Sentiment} size="sm" className="pointer-events-none" />
                {label}
              </span>
            ) : label}
          </button>
        ))}
      </div>

      {/* 3개월 이력 Pro 잠금 */}
      {!isPro && (
        <TierGate
          requiredTier="PRO"
          className="mb-2"
        >
          <div className="h-20 rounded-xl bg-muted/50 p-4 text-sm text-muted-foreground">
            3개월 이전 공시 이력 (Pro 전용)
          </div>
        </TierGate>
      )}

      {/* 피드 */}
      {isLoading && (
        <div className="py-12 text-center text-sm text-muted-foreground" role="status" aria-live="polite">
          공시를 불러오는 중...
        </div>
      )}

      {isError && (
        <div className="rounded-xl border border-destructive/30 bg-destructive/10 p-4 text-sm text-destructive" role="alert">
          공시를 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </div>
      )}

      {!isLoading && !isError && disclosures.length === 0 && (
        <div className="py-12 text-center text-sm text-muted-foreground">
          {filter === "ALL" ? "아직 공시가 없습니다." : `${FILTERS.find((f) => f.value === filter)?.label} 공시가 없습니다.`}
        </div>
      )}

      {Object.entries(groups).map(([dateLabel, items]) => (
        <section key={dateLabel} aria-label={`${dateLabel} 공시`}>
          <div className="mb-3 flex items-center gap-3">
            <h2 className="text-xs font-extrabold uppercase tracking-widest text-muted-foreground">{dateLabel}</h2>
            <span className="inline-flex h-5 items-center rounded-full bg-primary/10 px-2 text-[11px] font-extrabold text-primary">{items.length}</span>
            <span className="flex-1 border-t border-border" aria-hidden />
          </div>
          <ul className="flex flex-col gap-3">
            {items.map((d) => (
              <li key={d.id}>
                <DisclosureCard disclosure={d} />
              </li>
            ))}
          </ul>
        </section>
      ))}
    </div>
  );
}
