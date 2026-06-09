"use client";

// [목적] 모바일 상단 앱바 — 브랜드 로고 + 햄버거 메뉴 버튼
// [이유] 모바일에서 TopBar(검색·아바타 중심)를 단순화한 형태. 56px 고정 높이
// [사이드 임팩트] HamburgerDrawer의 drawerOpen 토글과 연결됨
// [수정 시 고려사항] 네이비 배경은 디자인 명세 기준. 타이틀 텍스트 없이 아이콘만 표시

import Link from "next/link";
import { Menu } from "lucide-react";
import { BrandMark } from "./BrandMark";
import { useUIStore } from "@/lib/stores/uiStore";

export function MobileAppBar() {
  const { setDrawerOpen } = useUIStore();

  return (
    <header className="flex h-14 shrink-0 items-center justify-between bg-[color:var(--color-brand-navy)] px-4">
      <Link href="/dashboard" className="flex items-center gap-2.5" aria-label="공시레이더 홈">
        <BrandMark size={30} className="!bg-white/10" />
        <div>
          <p className="text-[15px] font-extrabold leading-none tracking-tight text-white">공시레이더</p>
          <p className="text-[8px] font-semibold tracking-widest text-blue-300">DISCLOSURE RADAR</p>
        </div>
      </Link>

      <button
        type="button"
        onClick={() => setDrawerOpen(true)}
        className="flex size-10 flex-col items-center justify-center gap-1 rounded-lg transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/50"
        aria-label="전체 메뉴 열기"
      >
        <Menu className="size-5 text-blue-200" />
      </button>
    </header>
  );
}
