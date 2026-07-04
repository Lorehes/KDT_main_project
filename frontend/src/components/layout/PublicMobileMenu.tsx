"use client";

// [목적] PublicNavbar 모바일 전용 햄버거 드로어 — md 미만에서 숨겨진 글로벌 nav 링크를 Sheet로 제공
// [이유] PublicNavbar는 RSC(isAuthenticated 서버 prop)라 toggle state를 직접 가질 수 없음.
//   클라이언트 서브컴포넌트로 분리해 RSC 번들 이점을 유지하면서 모바일 접근성 해결.
// [사이드 임팩트] Sheet 컴포넌트(@base-ui 기반) 사용. overlay z-index가 PublicNavbar sticky(z-50)보다 높아야 함.
// [수정 시 고려사항] NAV_ITEMS 변경 시 PublicNavbar의 md:flex nav와 동기화 필요.
//   isAuthenticated prop이 추가되면 이 컴포넌트에도 함께 전달해야 CTA가 일관됨.

import { useState } from "react";
import Link from "next/link";
import { Menu, X } from "lucide-react";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { BrandMark } from "./BrandMark";
import { buttonVariants } from "@/components/ui/button";
import { PUBLIC_NAV_ITEMS } from "@/lib/navigation";

export function PublicMobileMenu({ isAuthenticated = false }: { isAuthenticated?: boolean }) {
  const [open, setOpen] = useState(false);

  return (
    <>
      <button
        type="button"
        onClick={() => setOpen(true)}
        className="grid size-9 place-items-center rounded-lg text-muted-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring md:hidden"
        aria-label="메뉴 열기"
        aria-expanded={open}
        aria-controls="public-mobile-nav"
      >
        <Menu className="size-5" aria-hidden />
      </button>

      <Sheet open={open} onOpenChange={setOpen}>
        <SheetContent side="right" className="w-72 p-0" id="public-mobile-nav">
          <SheetHeader className="border-b border-border px-5 py-4">
            <SheetTitle className="flex items-center gap-2.5">
              <BrandMark size={26} />
              <span className="text-base font-extrabold tracking-tight">공시레이더</span>
            </SheetTitle>
          </SheetHeader>

          <nav className="flex flex-col gap-0.5 p-3" aria-label="모바일 메뉴">
            {PUBLIC_NAV_ITEMS.map(({ href, label }) => (
              <Link
                key={href}
                href={href}
                onClick={() => setOpen(false)}
                className="rounded-[10px] px-4 py-3 text-sm font-semibold text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              >
                {label}
              </Link>
            ))}
          </nav>

          <div className="border-t border-border p-4 flex flex-col gap-2">
            {isAuthenticated ? (
              <Link
                href="/dashboard"
                onClick={() => setOpen(false)}
                className={buttonVariants({ size: "sm" }) + " w-full justify-center"}
              >
                대시보드로 →
              </Link>
            ) : (
              <>
                <Link
                  href="/login"
                  onClick={() => setOpen(false)}
                  className={buttonVariants({ variant: "outline", size: "sm" }) + " w-full justify-center"}
                >
                  로그인
                </Link>
                <Link
                  href="/signup"
                  onClick={() => setOpen(false)}
                  className={buttonVariants({ size: "sm" }) + " w-full justify-center"}
                >
                  무료로 시작
                </Link>
              </>
            )}
          </div>
        </SheetContent>
      </Sheet>
    </>
  );
}
