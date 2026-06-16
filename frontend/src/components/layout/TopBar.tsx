"use client";

// [목적] 웹 앱 상단 바 — 글로벌 검색·알림 벨·계정 메뉴 접근점
// [이유] 모든 인증 페이지에서 공통으로 표시되는 상단 네비게이션
// [사이드 임팩트] NotificationModal은 TopBar에서 직접 렌더(전역 state 연동 필요).
//   useUnreadCount: staleTime 30초 폴링 — 미읽음 있을 때만 빨간 점 표시.
//   markAsRead/markAllAsRead mutation 성공 시 ["unread-count"] 쿼리 무효화 → 자동 갱신.
// [수정 시 고려사항] WebSocket 도입 시 useUnreadCount 폴링 → 서버 푸시 구독으로 교체.

import Link from "next/link";
import { Bell, Search } from "lucide-react";
import { useAuthStore } from "@/lib/stores/authStore";
import { useUIStore } from "@/lib/stores/uiStore";
import { useUnreadCount } from "@/lib/api/notifications";

export function TopBar() {
  const { user, isLoading } = useAuthStore();
  const { toggleNotifModal } = useUIStore();
  const { data: unreadCount } = useUnreadCount();
  const initials = user?.nickname?.[0] ?? "";

  return (
    <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-background px-7">
      <div className="flex flex-1 items-center gap-3 pr-8">
        <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <input
          type="search"
          placeholder="종목명 또는 공시 검색"
          className="w-full max-w-[440px] bg-transparent text-sm text-muted-foreground outline-none placeholder:text-muted-foreground"
          aria-label="종목명 또는 공시 검색"
        />
      </div>

      <div className="flex items-center gap-4">
        <button
          type="button"
          onClick={toggleNotifModal}
          className="relative grid size-[42px] place-items-center rounded-[11px] bg-muted transition-colors hover:bg-muted/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label="알림 열기"
        >
          <Bell className="size-5 text-muted-foreground" />
          {(unreadCount ?? 0) > 0 && (
            <span className="absolute right-2.5 top-2.5 size-2 rounded-full bg-[color:var(--color-sentiment-negative)]" aria-label={`미읽음 알림 ${unreadCount}건`} />
          )}
        </button>

        <Link
          href="/settings"
          className="flex items-center gap-2.5 rounded-[11px] px-1.5 py-1 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label="계정 설정"
        >
          <div className="grid size-[38px] place-items-center rounded-[11px] bg-[color:var(--color-brand-navy)] font-extrabold text-sm text-white">
            {isLoading ? (
              <span className="size-3 rounded-full bg-white/40 animate-pulse" aria-hidden />
            ) : initials}
          </div>
          <div className="hidden sm:block">
            {isLoading ? (
              <div className="flex flex-col gap-1">
                <div className="h-3 w-16 rounded bg-muted animate-pulse" />
                <div className="h-2.5 w-10 rounded bg-muted animate-pulse" />
              </div>
            ) : (
              <>
                <p className="text-sm font-bold text-foreground">{user?.nickname ?? "사용자"}</p>
                <p className="text-[11px] text-muted-foreground">{user?.tier ?? "Free"} 멤버</p>
              </>
            )}
          </div>
        </Link>
      </div>
    </header>
  );
}
