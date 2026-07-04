"use client";

// [목적] 모바일 하단 탭바 — 대시보드·공시·종목·알림 4개 핵심 탭 네비게이션
// [이유] /disclosures(공시 피드)가 핵심 (USER) 섹션이므로 4번째 탭으로 추가.
//   /pricing은 HamburgerDrawer로 이동 — 모바일에서 공시 피드 직접 접근이 더 중요
// [사이드 임팩트] AppShell에서 md:hidden으로 제어됨. 웹에서는 숨겨짐
// [수정 시 고려사항] 탭 항목은 4개 고정(디자인 명세). 5개 이상은 HamburgerDrawer로 이동.
//   /pricing은 HamburgerDrawer MENU_ITEMS에 유지됨

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Bell } from "lucide-react";
import { cn } from "@/lib/utils";
import { isActivePath } from "@/lib/utils/isActivePath";
import { APP_NAV_ITEMS } from "@/lib/navigation";

// /notifications(알림) 탭은 모바일 탭바 전용 — APP_NAV_ITEMS에는 TopBar 알림 벨로 제공되므로 미포함
const TABS = [
  ...APP_NAV_ITEMS.map(({ href, labelShort: label, icon }) => ({ href, label, icon })),
  { href: "/notifications", label: "알림", icon: Bell },
] as const;

export function BottomTabBar() {
  const pathname = usePathname();

  return (
    <nav
      className="flex border-t border-border bg-background"
      aria-label="하단 탭 메뉴"
    >
      {TABS.map(({ href, label, icon: Icon }) => {
        const active = isActivePath(pathname, href);
        return (
          <Link
            key={href}
            href={href}
            className={cn(
              "flex flex-1 flex-col items-center gap-1.5 py-2.5 pb-3 text-[10.5px] font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              active ? "text-primary" : "text-muted-foreground",
            )}
            aria-current={active ? "page" : undefined}
            aria-label={label}
          >
            <Icon className="size-[22px]" aria-hidden />
            {label}
          </Link>
        );
      })}
    </nav>
  );
}
