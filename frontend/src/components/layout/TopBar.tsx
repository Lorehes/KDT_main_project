"use client";

// [목적] 웹 앱 상단 바 — 로고·글로벌 네비게이션·알림 벨·계정 메뉴 접근점
// [이유] 사이드바 제거 후 TopBar가 로고·주요 네비(대시보드/공시피드/포트폴리오)를 통합 수용
// [사이드 임팩트] NotificationModal은 TopBar에서 직접 렌더(전역 state 연동 필요).
//   useUnreadCount: staleTime 30초 폴링 — 미읽음 있을 때만 빨간 점 표시.
//   markAsRead/markAllAsRead mutation 성공 시 ["unread-count"] 쿼리 무효화 → 자동 갱신.
//   popoverOpen은 controlled로 관리 — pathname 변경(Next.js 클라이언트 라우팅) 시 자동 닫힘.
//   base-ui Popover는 내부 Link 클릭 시 dismiss 이벤트를 발생시키지 않으므로 useEffect로 보완.
// [수정 시 고려사항] WebSocket 도입 시 useUnreadCount 폴링 → 서버 푸시 구독으로 교체.
//   TIER_LABEL·NAV_ITEMS는 현재 로컬 상수 — docs/issues/topbar-settings-frontend-tech-debt.md #1·#2 참조.
//   검색창(searchQ) state는 TopBar 로컬 — URL 동기화 없음. Enter 시 router.push로 q param 전달.
//   모바일(md 미만) 검색창 숨김 — 모바일 검색은 후속 Spec에서 BottomTabBar 또는 검색 전용 페이지로 구현 예정.
//   form[role=search]의 submit 기본 동작은 handleSearch의 Enter key 처리로 대체 — onSubmit 연결 불필요(Enter가 form submit 트리거).

import { useState, useEffect, useCallback } from "react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { Bell, User, CreditCard, Info, LogOut, ChevronRight, Search } from "lucide-react";
import { Input } from "@/components/ui/input";
import { useAuthStore } from "@/lib/stores/authStore";
import { useUIStore } from "@/lib/stores/uiStore";
import { useUnreadCount } from "@/lib/api/notifications";
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover";
import { BrandMark } from "./BrandMark";
import { cn } from "@/lib/utils";
import { isActivePath } from "@/lib/utils/isActivePath";
import { TIER_LABEL_LONG } from "@/lib/constants";
import { APP_NAV_ITEMS } from "@/lib/navigation";

const PROFILE_MENU_ITEMS = [
  { icon: User,       label: "마이페이지",       href: "/settings" },
  { icon: Bell,       label: "알림 설정",         href: "/notifications/settings" },
  { icon: CreditCard, label: "요금제 · 구독 관리", href: "/pricing" },
  { icon: Info,       label: "공지사항 · 고객센터", href: "/support" },
] as const;

export function TopBar() {
  const pathname = usePathname();
  const router   = useRouter();
  // 셀렉터 단위 구독 — 불필요한 리렌더 방지 (authStore 전체 구독 → 필드별 분리)
  const user             = useAuthStore(s => s.user);
  const isLoading        = useAuthStore(s => s.isLoading);
  const logout           = useAuthStore(s => s.logout);
  const toggleNotifModal = useUIStore(s => s.toggleNotifModal);
  const { data: unreadCount } = useUnreadCount();
  const initials = user?.nickname?.[0] ?? "";
  const tierLabel = user?.tier ? (TIER_LABEL_LONG[user.tier] ?? user.tier) : "Free 플랜";

  // Popover controlled state — pathname 변경(클라이언트 라우팅) 시 자동 닫힘
  const [popoverOpen, setPopoverOpen] = useState(false);
  useEffect(() => { setPopoverOpen(false); }, [pathname]);

  // 검색창 상태 — Enter 시 /disclosures?q=... 라우팅 후 초기화
  const [searchQ, setSearchQ] = useState("");
  const handleSearch = useCallback((e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key !== "Enter" || !searchQ.trim()) return;
    router.push(`/disclosures?q=${encodeURIComponent(searchQ.trim())}`);
    setSearchQ("");
  }, [searchQ, router]);

  return (
    <header className="flex h-16 shrink-0 items-center justify-between border-b border-border bg-background px-6">
      {/* 좌측: 로고 + 글로벌 네비 */}
      <div className="flex items-center gap-6">
        <Link href="/dashboard" className="flex items-center gap-2.5 shrink-0">
          <BrandMark size={28} />
          <span className="text-[15px] font-extrabold tracking-tight text-[color:var(--color-brand-navy)]">
            공시레이더
          </span>
        </Link>

        <nav className="flex items-center gap-1" aria-label="주요 메뉴">
          {APP_NAV_ITEMS.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              className={cn(
                "flex items-center gap-2 rounded-[10px] px-3 py-2 text-[13.5px] font-bold transition-colors",
                isActivePath(pathname, href)
                  ? "bg-primary/10 text-primary"
                  : "text-foreground hover:bg-muted",
              )}
              aria-current={isActivePath(pathname, href) ? "page" : undefined}
            >
              <Icon className="size-4 shrink-0" aria-hidden />
              {label}
            </Link>
          ))}
        </nav>
      </div>

      {/* 중앙: 글로벌 검색창 — 모바일(md 미만) 숨김 */}
      <form role="search" className="hidden md:flex flex-1 max-w-xs mx-4" onSubmit={e => e.preventDefault()}>
        <div className="relative w-full">
          <Search className="absolute left-3 top-1/2 -translate-y-1/2 size-4 text-muted-foreground pointer-events-none" aria-hidden />
          <Input
            type="search"
            value={searchQ}
            onChange={e => setSearchQ(e.target.value)}
            onKeyDown={handleSearch}
            placeholder="공시·종목명 검색"
            aria-label="공시·종목명 검색"
            className="pl-9 h-9 text-sm"
          />
        </div>
      </form>

      {/* 우측: 알림 + 프로필 */}
      <div className="flex items-center gap-4">
        <button
          type="button"
          onClick={toggleNotifModal}
          className="relative grid size-[42px] place-items-center rounded-[11px] bg-muted transition-colors hover:bg-muted/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          aria-label={unreadCount ? `알림 열기 (미읽음 ${unreadCount}건)` : "알림 열기"}
        >
          <Bell className="size-5 text-muted-foreground" />
          {(unreadCount ?? 0) > 0 && (
            <span className="absolute right-2.5 top-2.5 size-2 rounded-full bg-[color:var(--color-sentiment-negative)]" aria-label={`미읽음 알림 ${unreadCount}건`} />
          )}
        </button>

        <Popover open={popoverOpen} onOpenChange={setPopoverOpen}>
          <PopoverTrigger
            className="flex items-center gap-2.5 rounded-[11px] px-1.5 py-1 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label="계정 메뉴 열기"
          >
            <div className="grid size-[38px] place-items-center rounded-[11px] bg-[color:var(--color-brand-navy)] font-extrabold text-sm text-white select-none">
              {isLoading ? (
                <span className="size-3 rounded-full bg-white/40 animate-pulse" aria-hidden />
              ) : (initials || <User className="size-4 text-white/70" aria-hidden />)}
            </div>
            <div className="hidden sm:flex sm:flex-col sm:items-start">
              {isLoading ? (
                <div className="flex flex-col gap-1">
                  <div className="h-3 w-16 rounded bg-muted animate-pulse" />
                  <div className="h-2.5 w-10 rounded bg-muted animate-pulse" />
                </div>
              ) : (
                <>
                  <p className="text-sm font-bold text-foreground leading-tight">{user?.nickname ?? "사용자"}</p>
                  <p className="text-[11px] text-muted-foreground leading-tight">{user?.tier ?? "Free"} 멤버</p>
                </>
              )}
            </div>
          </PopoverTrigger>

          <PopoverContent
            side="bottom"
            align="end"
            sideOffset={8}
            className="w-72 p-0 overflow-hidden rounded-xl shadow-lg"
          >
            {/* 헤더 — 네이비 배경 */}
            <div className="bg-[color:var(--color-brand-navy)] px-5 py-4 flex items-center gap-3">
              <div className="grid size-11 shrink-0 place-items-center rounded-xl bg-white/10 font-extrabold text-lg text-white select-none">
                {initials || <User className="size-5 text-white/60" aria-hidden />}
              </div>
              <div className="min-w-0">
                <p className="text-sm font-bold text-white truncate">{user?.nickname ?? "사용자"}님</p>
                <p className="text-[11px] text-white/60 truncate">{user?.email ?? ""}</p>
                <span className="mt-1.5 inline-block rounded border border-white/30 px-2 py-0.5 text-[10px] font-semibold text-white/90">
                  {tierLabel}
                </span>
              </div>
            </div>

            {/* 계정 메뉴 */}
            <div className="py-2">
              <p className="px-5 pb-1 pt-2 text-[11px] font-semibold text-muted-foreground uppercase tracking-wider">계정</p>
              {PROFILE_MENU_ITEMS.map(({ icon: Icon, label, href }) => (
                <Link
                  key={href}
                  href={href}
                  onClick={() => setPopoverOpen(false)}
                  className="flex items-center gap-3 px-5 py-3 text-sm text-foreground hover:bg-muted transition-colors focus-visible:outline-none focus-visible:bg-muted"
                >
                  <Icon className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                  <span className="flex-1">{label}</span>
                  <ChevronRight className="size-3.5 shrink-0 text-muted-foreground/50" aria-hidden />
                </Link>
              ))}
            </div>

            {/* 구분선 + 로그아웃 */}
            <div className="border-t border-border py-2">
              <button
                type="button"
                onClick={() => logout()}
                className="flex w-full items-center gap-3 px-5 py-3 text-sm font-semibold text-[color:var(--color-brand-blue)] hover:bg-muted transition-colors focus-visible:outline-none focus-visible:bg-muted"
              >
                <LogOut className="size-4 shrink-0" aria-hidden />
                로그아웃
              </button>
            </div>
          </PopoverContent>
        </Popover>
      </div>
    </header>
  );
}
