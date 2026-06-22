"use client";

// [목적] 공시 피드 페이지(D15/m23) — 전체 공시를 필터·날짜 그룹으로 탐색, 페이지 누적 "더 보기"
// [이유] 대시보드는 보유 종목 공시만 표시. 이 페이지는 전체 공시 탐색 + 감성 필터 제공
// [사이드 임팩트] useDisclosures 쿼리를 sentiment·scope·page 파라미터로 제어. TierGate는 Pro 미달 시 3개월 이력 차단
// [수정 시 고려사항] 날짜 그룹(오늘·어제·이번주)은 클라이언트에서 rcept_dt 파싱.
//   R4 hasMore 가드: content.length < SIZE → 서버 데이터 고갈 → "더 보기" 숨김. R3(BE JOIN) 완료 후에도 유지 가능.
//   필터 상태는 URL searchParams로 관리 권장(현재 로컬 state).

import { useState, useEffect, useRef } from "react";
import { useDisclosures, type Sentiment, type Disclosure } from "@/lib/api/disclosures";
import { useTierCheck } from "@/lib/hooks/useTierCheck";
import { DisclosureCard } from "@/components/domain/DisclosureCard";
import { TierGate } from "@/components/domain/TierGate";
import { SentimentBadge } from "@/components/domain/SentimentBadge";

type FilterType = "ALL" | Sentiment | "WITHHELD";

const FILTERS: { value: FilterType; label: string }[] = [
  { value: "ALL",      label: "전체" },
  { value: "POSITIVE", label: "호재" },
  { value: "NEGATIVE", label: "악재" },
  { value: "NEUTRAL",  label: "중립" },
  { value: "WITHHELD", label: "보류" },
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

const PAGE_SIZE = 30;

export default function DisclosuresFeedPage() {
  const { isPro } = useTierCheck();
  const [filter, setFilter] = useState<FilterType>("ALL");

  // R4: 페이지 누적 상태 — 필터 변경 시 리셋
  const [page, setPage] = useState(0);
  const [allItems, setAllItems] = useState<Disclosure[]>([]);
  const filterRef = useRef(filter);

  const { data, isLoading, isError, isFetching } = useDisclosures({
    scope: "portfolio",
    // WITHHELD(보류)는 sentiment 값이 아니라 is_withheld 플래그 — 전용 withheld 파라미터로 분리 전송.
    sentiment: filter === "ALL" || filter === "WITHHELD" ? undefined : filter,
    withheld: filter === "WITHHELD" ? true : undefined,
    size: PAGE_SIZE,
    page,
  });

  // 필터 변경 시 페이지·누적 데이터 리셋
  useEffect(() => {
    if (filterRef.current !== filter) {
      filterRef.current = filter;
      setPage(0);
      setAllItems([]);
    }
  }, [filter]);

  // 새 페이지 데이터 누적 — data + page를 모두 의존성으로 선언해 stale closure 방지
  // (filter 변경 시 setPage(0) 큐가 커밋되기 전에 data effect가 실행되는 race condition 차단)
  useEffect(() => {
    if (data?.content) {
      setAllItems(prev => page === 0 ? data.content : [...prev, ...data.content]);
    }
  }, [data, page]);

  // R4: content.length < PAGE_SIZE → 마지막 페이지 감지. isFetching과 분리해 버튼 가시성 유지
  const canLoadMore = (data?.content.length ?? 0) >= PAGE_SIZE;

  const disclosures = allItems;
  const groups = groupByDate(disclosures);

  return (
    <div className="flex flex-col gap-6">
      {/* 헤더 */}
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight text-foreground">공시 피드</h1>
        <p className="mt-1 text-sm text-muted-foreground">보유 종목의 공시를 날짜별로 확인하세요.</p>
      </div>

      {/* 필터 칩 — overflow-x-auto가 세로도 클리핑하므로 선택 링(ring-offset)이 잘리지 않게 py 여백 확보.
          -mx-1 px-1로 링 좌우 여백을 주되 헤더와의 정렬은 유지 */}
      <div className="-mx-1 flex gap-2 overflow-x-auto px-1 py-2" role="group" aria-label="공시 감성 필터">
        {FILTERS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            onClick={() => setFilter(value)}
            aria-pressed={filter === value}
            aria-label={`${label} 필터`}
            className={`shrink-0 rounded-full transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring ${
              value === "ALL"
                ? `border px-4 py-2 text-sm font-bold ${
                    filter === value
                      ? "border-primary bg-primary text-primary-foreground"
                      : "border-border bg-background text-muted-foreground hover:bg-muted"
                  }`
                : `hover:opacity-90 ${
                    filter === value ? "ring-2 ring-offset-2 ring-offset-background ring-ring" : ""
                  }`
            }`}
          >
            {value !== "ALL" ? (
              <SentimentBadge
                sentiment={value === "WITHHELD" ? "NEUTRAL" : (value as Sentiment)}
                isWithheld={value === "WITHHELD"}
                size="lg"
                className="pointer-events-none"
              />
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

      {/* R4: content.length >= PAGE_SIZE 일 때만 "더 보기" 표시 — isFetching 중에는 disabled로 유지(소실 방지) */}
      {canLoadMore && (
        <div className="flex justify-center pt-2">
          <button
            type="button"
            onClick={() => setPage(p => p + 1)}
            disabled={isFetching}
            className="rounded-full border border-border bg-background px-6 py-2.5 text-sm font-bold text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-50"
            aria-label="공시 더 보기"
          >
            {isFetching ? "불러오는 중..." : "더 보기"}
          </button>
        </div>
      )}
    </div>
  );
}
