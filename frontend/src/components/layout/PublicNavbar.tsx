// [목적] 랜딩·요금제 등 퍼블릭 페이지 상단 네비바 — 브랜드·메뉴·CTA
// [이유] 인증 셸과 분리된 마케팅용 네비. 로그인 사용자(isAuthenticated)에겐 '로그인/무료로 시작' 대신
//   '대시보드로' CTA를 노출 — 로그인 상태로 /pricing 등에 진입했을 때 비로그인 CTA가 뜨는 어색함 방지.
// [사이드 임팩트] (public)/layout.tsx에서만 사용(인증 상태를 서버 prop으로 주입). 앱 셸 TopBar와 독립적.
//   서버 prop 기반이라 클라이언트 컴포넌트일 필요가 없어 'use client' 제거 — RSC로 렌더(번들 감소).
// [수정 시 고려사항] isAuthenticated는 dr_session presence(httpOnly)라 서버에서만 판정 가능 → 반드시 prop으로 받음.
//   닉네임/아바타 등 인증 사용자 정보를 더 노출하려면 별도 클라이언트 서브컴포넌트(useAuthStore)로 분리.
//   스크롤에 따른 배경 불투명도 변화 추가 시 scroll listener 또는 CSS backdrop-blur 활용.

import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";
import { BrandMark } from "@/components/layout/BrandMark";

export function PublicNavbar({ isAuthenticated = false }: { isAuthenticated?: boolean }) {
  return (
    <header className="sticky top-0 z-50 border-b border-border bg-background/95 backdrop-blur-sm">
      <div className="mx-auto flex h-16 max-w-7xl items-center justify-between px-6">
        <Link href="/" className="flex items-center gap-2.5">
          <BrandMark size={36} />
          <span className="text-[17px] font-extrabold tracking-tight text-[color:var(--color-brand-navy)]">
            공시레이더
            <small className="ml-1 block text-[8px] font-semibold tracking-widest text-muted-foreground">
              DISCLOSURE RADAR
            </small>
          </span>
        </Link>

        <nav className="hidden items-center gap-7 text-sm font-semibold text-muted-foreground md:flex">
          <Link href="/#features" className="transition-colors hover:text-foreground">기능</Link>
          <Link href="/pricing" className="transition-colors hover:text-foreground">요금제</Link>
          <Link href="/#cases" className="transition-colors hover:text-foreground">고객사례</Link>
          <Link href="/#help" className="transition-colors hover:text-foreground">도움말</Link>
        </nav>

        <div className="flex items-center gap-2">
          {isAuthenticated ? (
            <Link
              href="/dashboard"
              className={buttonVariants({ size: "sm" })}
              aria-label="대시보드로 이동"
            >
              대시보드로 →
            </Link>
          ) : (
            <>
              <Link href="/login" className={buttonVariants({ variant: "ghost", size: "sm" })}>
                로그인
              </Link>
              <Link href="/signup" className={buttonVariants({ size: "sm" })}>
                무료로 시작
              </Link>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
