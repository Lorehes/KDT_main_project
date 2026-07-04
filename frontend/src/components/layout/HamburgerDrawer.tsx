"use client";

// [목적] 모바일 햄버거 드로어 — 우측 슬라이드 전체 메뉴 허브
// [이유] 모바일 탭바가 4개 항목만 담으므로, 나머지 메뉴(마이페이지·알림설정·요금제 등)를 드로어로 제공
// [사이드 임팩트] uiStore.drawerOpen과 연동. AppBar의 햄버거 버튼이 토글
// [수정 시 고려사항] Sheet 컴포넌트(shadcn) 사용. 접근성: ESC 닫기·포커스 트랩은 Sheet가 처리

import Link from "next/link";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { useUIStore } from "@/lib/stores/uiStore";
import { useAuthStore } from "@/lib/stores/authStore";
import { BrandMark } from "./BrandMark";
import { User, LogOut } from "lucide-react";
import { APP_NAV_ITEMS } from "@/lib/navigation";

// /settings(마이페이지)는 앱 공통 nav 외 드로어 전용 항목 — APP_NAV_ITEMS에 미포함
const EXTRA_MENU_ITEMS = [
  { href: "/settings", label: "마이페이지", icon: User },
] as const;

const MENU_ITEMS = [...APP_NAV_ITEMS, ...EXTRA_MENU_ITEMS];

export function HamburgerDrawer() {
  const { drawerOpen, setDrawerOpen } = useUIStore();
  const { user, logout } = useAuthStore();

  return (
    <Sheet open={drawerOpen} onOpenChange={setDrawerOpen}>
      <SheetContent side="right" className="w-72 p-0">
        <SheetHeader className="border-b border-border p-5">
          <SheetTitle className="flex items-center gap-2.5">
            <BrandMark size={28} />
            <span className="text-base font-extrabold tracking-tight">공시레이더</span>
          </SheetTitle>
          {user && (
            <div className="mt-3 flex items-center gap-3">
              <div className="grid size-10 place-items-center rounded-xl bg-[color:var(--color-brand-navy)] font-extrabold text-sm text-white">
                {user.nickname?.[0] ?? "?"}
              </div>
              <div>
                <p className="text-sm font-bold">{user.nickname}</p>
                <p className="text-xs text-muted-foreground">{user.tier} 멤버</p>
              </div>
            </div>
          )}
        </SheetHeader>

        <nav className="flex flex-col gap-0.5 p-3" aria-label="전체 메뉴">
          {MENU_ITEMS.map(({ href, label, icon: Icon }) => (
            <Link
              key={href}
              href={href}
              onClick={() => setDrawerOpen(false)}
              className="flex items-center gap-3 rounded-[11px] px-3 py-3 text-[14px] font-bold text-foreground transition-colors hover:bg-muted"
            >
              <Icon className="size-5 shrink-0 text-muted-foreground" aria-hidden />
              {label}
            </Link>
          ))}
        </nav>

        <div className="border-t border-border p-3">
          <button
            type="button"
            onClick={logout}
            className="flex w-full items-center gap-3 rounded-[11px] px-3 py-3 text-[14px] font-bold text-destructive transition-colors hover:bg-destructive/10"
          >
            <LogOut className="size-5 shrink-0" aria-hidden />
            로그아웃
          </button>
        </div>
      </SheetContent>
    </Sheet>
  );
}
