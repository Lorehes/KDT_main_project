"use client";

// [목적] 알림 센터(D24/m26) — 전체 알림 이력을 날짜 그룹·필터로 탐색
// [이유] 하단 탭바 '알림' 탭의 핵심 화면. 발송된 모든 알림 인박스 역할
// [사이드 임팩트] useNotifications 쿼리(전체). 읽음 처리는 useMarkAsRead/useMarkAllAsRead mutation(서버 영속).
//   is_read 서버 상태 기반 → 새로고침·탭 이동 후에도 읽음 상태 유지.
//   mutation 성공 시 ["notifications", "unread-count"] 쿼리 자동 무효화.
// [수정 시 고려사항] 날짜 그룹은 created_at 기준 클라이언트 파싱.
//   안읽음 필터(UNREAD)는 is_read 서버 상태 기반 로컬 필터.
//   온보딩 미완료 배너: 포트폴리오 미등록 또는 알림 비활성화 시 상단 안내 표시.
//   종목 등록 배너의 "등록" 버튼은 페이지 이탈 없이 PortfolioSheet(bottom side, BOTTOM_SHEET_MIN_HEIGHT) 오픈.
//   sheetSide는 useSheetSide() 훅이 관리 — matchMedia 로직 중복 없음.

import { useState } from "react";
import { useSheetSide, BOTTOM_SHEET_MIN_HEIGHT } from "@/hooks/useSheetSide";
import Link from "next/link";
import { Settings2, AlertTriangle } from "lucide-react";
import { useNotifications, useMarkAsRead, useMarkAllAsRead, useNotificationSettings } from "@/lib/api/notifications";
import { usePortfolios } from "@/lib/api/portfolios";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { PortfolioSheet } from "@/components/domain/PortfolioSheet";
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import { Skeleton } from "@/components/ui/skeleton";
import { buttonVariants } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import type { Notification } from "@/lib/api/notifications";

type FilterType = "ALL" | "UNREAD" | "POSITIVE" | "NEGATIVE";

const FILTERS: { value: FilterType; label: string }[] = [
  { value: "ALL",      label: "전체" },
  { value: "UNREAD",   label: "안읽음" },
  { value: "POSITIVE", label: "호재" },
  { value: "NEGATIVE", label: "악재" },
];

function groupByDate(notifications: Notification[]) {
  const today = new Date().toLocaleDateString("ko-KR");
  const yesterday = new Date(Date.now() - 86400000).toLocaleDateString("ko-KR");
  const groups: Record<string, Notification[]> = {};

  notifications.forEach((n) => {
    const d = new Date(n.created_at).toLocaleDateString("ko-KR");
    const label = d === today ? "오늘" : d === yesterday ? "어제" : d;
    if (!groups[label]) groups[label] = [];
    groups[label].push(n);
  });
  return groups;
}

export default function NotificationsPage() {
  const { data, isLoading } = useNotifications();
  const showSkeleton = useDelayedLoading(isLoading);
  const [filter, setFilter] = useState<FilterType>("ALL");
  const markAsRead    = useMarkAsRead();
  const markAllAsRead = useMarkAllAsRead();

  const { data: portfolios, isLoading: portfoliosLoading } = usePortfolios();
  const { data: settings,   isLoading: settingsLoading   } = useNotificationSettings();

  const [portfolioSheetOpen, setPortfolioSheetOpen] = useState(false);
  const sheetSide = useSheetSide();

  const showNoPortfolio = !portfoliosLoading && portfolios !== undefined && portfolios.length === 0;
  const showNoNotif     = !settingsLoading  && settings  !== undefined && !settings.enabled;

  const all = data?.content ?? [];

  const filtered = all.filter((n) => {
    if (filter === "UNREAD") return !n.is_read;
    if (filter === "POSITIVE") return n.sentiment === "POSITIVE";
    if (filter === "NEGATIVE") return n.sentiment === "NEGATIVE";
    return true;
  });

  const groups = groupByDate(filtered);
  const unreadCount = all.filter((n) => !n.is_read).length;

  const handleMarkRead    = (id: number, isRead: boolean) => { if (!isRead) markAsRead.mutate(id); };
  const handleMarkAllRead = () => markAllAsRead.mutate();

  return (
    <>
    <div className="flex flex-col gap-5">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-extrabold tracking-tight text-foreground">알림 센터</h1>
          {unreadCount > 0 && (
            <p className="mt-0.5 text-sm text-muted-foreground">안읽음 {unreadCount}건</p>
          )}
        </div>
        <div className="flex items-center gap-2">
          {unreadCount > 0 && (
            <button
              type="button"
              onClick={handleMarkAllRead}
              disabled={markAllAsRead.isPending}
              className="text-sm font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50"
            >
              모두 읽음
            </button>
          )}
          <Link href="/notifications/settings" className={buttonVariants({ variant: "outline", size: "sm" })} aria-label="알림 설정">
            <Settings2 className="size-4" aria-hidden />
            설정
          </Link>
        </div>
      </div>

      {/* 온보딩 미완료 안내 배너 */}
      {(showNoPortfolio || showNoNotif) && (
        <div className="flex flex-col gap-2" role="group" aria-label="설정 안내">
          {showNoPortfolio && (
            <div
              role="alert"
              className="flex items-center gap-3 rounded-xl border border-border bg-muted px-4 py-3"
            >
              <AlertTriangle className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-foreground">종목 미등록</p>
                <p className="text-sm text-muted-foreground">보유 종목이 등록되지 않아 공시 알림을 받을 수 없습니다.</p>
              </div>
              <button
                type="button"
                onClick={() => setPortfolioSheetOpen(true)}
                className={buttonVariants({ variant: "outline", size: "sm" })}
                aria-label="종목 등록 Sheet 열기"
              >
                종목 등록
              </button>
            </div>
          )}
          {showNoNotif && (
            <div
              role="alert"
              className="flex items-center gap-3 rounded-xl border border-border bg-muted px-4 py-3"
            >
              <AlertTriangle className="size-4 shrink-0 text-muted-foreground" aria-hidden />
              <div className="min-w-0 flex-1">
                <p className="text-sm font-bold text-foreground">알림 미설정</p>
                <p className="text-sm text-muted-foreground">알림이 꺼져 있어 공시가 도착해도 받을 수 없습니다.</p>
              </div>
              <Link
                href="/notifications/settings"
                className={buttonVariants({ variant: "outline", size: "sm" })}
                aria-label="알림 설정 페이지로 이동"
              >
                알림 설정
              </Link>
            </div>
          )}
        </div>
      )}

      {/* 필터 칩 */}
      <div className="flex gap-2 overflow-x-auto pb-1" role="group" aria-label="알림 필터">
        {FILTERS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            aria-pressed={filter === value}
            onClick={() => setFilter(value)}
            className={cn(
              "shrink-0 rounded-full border px-4 py-2 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              filter === value
                ? "border-primary bg-primary text-primary-foreground"
                : "border-border bg-background text-muted-foreground hover:bg-muted",
            )}
          >
            {label}
            {value === "UNREAD" && unreadCount > 0 && (
              <span className="ml-1.5 inline-flex h-4 min-w-[16px] items-center justify-center rounded-full bg-primary-foreground/20 px-1 text-[10px] font-extrabold">
                {unreadCount}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* 알림 목록 */}
      {showSkeleton ? (
        <div className="flex flex-col gap-0 overflow-hidden rounded-2xl border border-border bg-card shadow-sm" role="status" aria-label="알림 불러오는 중">
          {[...Array(5)].map((_, i) => (
            <div key={i} className="flex items-start gap-3 border-b border-border last:border-b-0 px-5 py-4">
              <Skeleton className="size-11 shrink-0 rounded-xl" />
              <div className="flex flex-1 flex-col gap-2">
                <div className="flex items-center gap-2">
                  <Skeleton className="h-4 w-20" />
                  <Skeleton className="h-5 w-10 rounded-full" />
                </div>
                <Skeleton className="h-3.5 w-full" />
                <Skeleton className="h-3 w-16" />
              </div>
            </div>
          ))}
        </div>
      ) : isLoading ? null : filtered.length === 0 ? (
        <div className="py-12 text-center text-sm text-muted-foreground">
          {filter === "UNREAD" ? "안읽은 알림이 없습니다." : "알림이 없습니다."}
        </div>
      ) : (
        Object.entries(groups).map(([dateLabel, items]) => (
          <section key={dateLabel} aria-label={`${dateLabel} 알림`}>
            {/* 날짜 그룹 헤더 */}
            <div className="mb-2 flex items-center gap-2.5">
              <p className="text-xs font-extrabold uppercase tracking-widest text-muted-foreground">{dateLabel}</p>
              <span className="inline-flex h-5 items-center rounded-full bg-primary/10 px-2 text-[11px] font-extrabold text-primary">{items.length}</span>
              <span className="flex-1 border-t border-border" aria-hidden />
            </div>

            <ul className="overflow-hidden rounded-2xl border border-border bg-card shadow-sm" role="list">
              {items.map((notif) => {
                const isRead = notif.is_read;
                return (
                  <li
                    key={notif.id}
                    className={cn("border-b border-border last:border-b-0", isRead && "opacity-60")}
                  >
                    <Link
                      href={`/disclosures/${notif.disclosure_id}`}
                      onClick={() => handleMarkRead(notif.id, isRead)}
                      className="flex gap-3 px-5 py-4 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                      aria-label={`${notif.corp_name} 공시 상세 보기${!isRead ? " (안읽음)" : ""}`}
                    >
                      <div className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary font-extrabold text-sm text-primary-foreground" aria-hidden>
                        {notif.corp_name.slice(0, 2)}
                      </div>

                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-1.5">
                          <span className="text-sm font-bold text-foreground">{notif.corp_name}</span>
                          <SentimentBadge sentiment={notif.sentiment} size="sm" />
                        </div>
                        <p className="mt-0.5 line-clamp-2 text-sm text-muted-foreground">{notif.report_nm}</p>
                        <time
                          className="mt-1 block text-xs text-muted-foreground"
                          dateTime={notif.created_at}
                        >
                          {new Date(notif.created_at).toLocaleString("ko-KR", {
                            hour: "2-digit", minute: "2-digit",
                          })}
                        </time>
                      </div>

                      {!isRead && (
                        <span className="mt-1.5 size-2.5 shrink-0 rounded-full bg-primary" aria-hidden />
                      )}
                    </Link>
                  </li>
                );
              })}
            </ul>
          </section>
        ))
      )}
    </div>

      <PortfolioSheet
        open={portfolioSheetOpen}
        onOpenChange={setPortfolioSheetOpen}
        side={sheetSide}
        contentClassName={sheetSide === "bottom" ? BOTTOM_SHEET_MIN_HEIGHT : undefined}
      />
    </>
  );
}
