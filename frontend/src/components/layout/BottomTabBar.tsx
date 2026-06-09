"use client";

// [목적] 모바일 하단 탭바 — 대시보드·종목·알림·요금제 탭 네비게이션
// [이유] 모바일(< md)에서 앱의 4개 핵심 섹션에 즉시 접근하는 표준 모바일 패턴
// [사이드 임팩트] AppShell에서 md:hidden으로 제어됨. 웹에서는 숨겨짐
// [수정 시 고려사항] 탭 항목은 4개 고정(디자인 명세). 5개 이상은 HamburgerDrawer로 이동

import Link from "next/link";
import { usePathname } from "next/navigation";
import { LayoutDashboard, Briefcase, Bell, CreditCard } from "lucide-react";
import { cn } from "@/lib/utils";

const TABS = [
  { href: "/dashboard", label: "대시보드", icon: LayoutDashboard },
  { href: "/portfolios", label: "종목", icon: Briefcase },
  { href: "/notifications", label: "알림", icon: Bell },
  { href: "/pricing", label: "요금제", icon: CreditCard },
];

export function BottomTabBar() {
  const pathname = usePathname();

  return (
    <nav
      className="flex border-t border-border bg-background"
      aria-label="하단 탭 메뉴"
    >
      {TABS.map(({ href, label, icon: Icon }) => {
        const active = pathname === href || (href !== "/dashboard" && pathname.startsWith(href));
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
