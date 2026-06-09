"use client";

// [목적] 웹 앱 좌측 글로벌 사이드바 — 대시보드·공시·포트폴리오·알림·설정 네비게이션
// [이유] 웹(md 이상)에서 고정 240px 사이드바로 앱의 모든 주요 섹션에 접근
// [사이드 임팩트] AppShell 내부에서 hidden md:flex로 제어됨. 모바일에서는 HamburgerDrawer가 대체
// [수정 시 고려사항] 현재 활성 경로 강조는 usePathname()으로 판단. 새 라우트 추가 시 NAV_ITEMS 배열에 추가

import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  LayoutDashboard,
  FileText,
  Briefcase,
  Bell,
  CreditCard,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { BrandMark } from "./BrandMark";
import { useAuthStore } from "@/lib/stores/authStore";
import { buttonVariants } from "@/components/ui/button";

// /portfolios/new는 NAV_ITEMS에서 제거 — /portfolios 내부 CTA로만 제공(IA 계층 정합)
// /notifications(이력)을 최상위 메뉴에 추가 — design_structure §1 IA 기준
const NAV_ITEMS = [
  { href: "/dashboard",    label: "대시보드",    icon: LayoutDashboard },
  { href: "/disclosures",  label: "공시 피드",   icon: FileText },
  { href: "/portfolios",   label: "내 포트폴리오", icon: Briefcase },
  { href: "/notifications", label: "알림",       icon: Bell },
];

const SETTING_ITEMS = [
  { href: "/notifications/settings", label: "알림 설정", icon: Bell },
  { href: "/pricing", label: "요금제", icon: CreditCard },
];

export function Sidebar() {
  const pathname = usePathname();
  const { user } = useAuthStore();
  const isPro = user?.tier === "PRO" || user?.tier === "PREMIUM";

  return (
    <aside className="flex w-60 flex-col border-r border-border bg-background px-3.5 py-5">
      <Link href="/dashboard" className="mb-4 flex items-center gap-2.5 px-3">
        <BrandMark size={32} />
        <span className="text-base font-extrabold tracking-tight text-[color:var(--color-brand-navy)]">
          공시레이더
        </span>
      </Link>

      <nav className="flex flex-col gap-0.5" aria-label="주요 메뉴">
        {NAV_ITEMS.map(({ href, label, icon: Icon }) => (
          <Link
            key={href}
            href={href}
            className={cn(
              "flex items-center gap-3 rounded-[11px] px-3 py-3 text-[14.5px] font-bold transition-colors",
              pathname === href || (href !== "/dashboard" && pathname.startsWith(href))
                ? "bg-primary/10 text-primary"
                : "text-foreground hover:bg-muted",
            )}
            aria-current={pathname === href ? "page" : undefined}
          >
            <Icon className="size-5 shrink-0" />
            {label}
          </Link>
        ))}
      </nav>

      <p className="mt-5 px-3 pb-1.5 text-[10px] font-extrabold uppercase tracking-widest text-muted-foreground">
        설정
      </p>
      <nav className="flex flex-col gap-0.5" aria-label="설정 메뉴">
        {SETTING_ITEMS.map(({ href, label, icon: Icon }) => (
          <Link
            key={href}
            href={href}
            className={cn(
              "flex items-center gap-3 rounded-[11px] px-3 py-3 text-[14.5px] font-bold transition-colors",
              pathname.startsWith(href)
                ? "bg-primary/10 text-primary"
                : "text-foreground hover:bg-muted",
            )}
          >
            <Icon className="size-5 shrink-0" />
            {label}
          </Link>
        ))}
      </nav>

      <div className="mt-auto rounded-2xl bg-[color:var(--color-brand-navy)] p-4 text-white">
        {isPro ? (
          <>
            <p className="text-[13px] font-extrabold">{user?.tier} 이용 중</p>
            <p className="mt-1 text-[11.5px] text-blue-200">무제한 종목 · 심층 분석</p>
            <Link
              href="/settings"
              className={buttonVariants({ size: "sm", variant: "ghost" }) + " mt-3 w-full text-white hover:bg-white/10"}
            >
              구독 관리
            </Link>
          </>
        ) : (
          <>
            <p className="text-[13px] font-extrabold">Free 플랜</p>
            <p className="mt-1 text-[11.5px] text-blue-200">종목 3개 · 일 5건 알림</p>
            <Link href="/pricing" className={buttonVariants({ size: "sm" }) + " mt-3 w-full"}>
              Pro 업그레이드
            </Link>
          </>
        )}
      </div>
    </aside>
  );
}
