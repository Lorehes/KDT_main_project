"use client";

// [목적] 인증 앱 전체 셸 — 웹(Sidebar+TopBar) / 모바일(AppBar+BottomTabBar)를 단일 컴포넌트로 제공
// [이유] md 브레이크포인트 하나로 웹·모바일 레이아웃을 전환해 중복 라우트·layout 없이 반응형 처리
// [사이드 임팩트] (app)/layout.tsx에서 래핑. 하위 모든 page가 이 셸을 상속.
//   스킵 네비게이션 링크가 DOM 최상단에 위치 — Tab 첫 순서가 스킵 링크(WCAG 2.4.1)
// [수정 시 고려사항] 웹: min-h-screen flex. 모바일: flex-col h-dvh overflow-hidden.
//   스크롤 영역은 메인 컨텐츠 영역만(overflow-y-auto). 탭바·사이드바는 고정.
//   모바일 main id="main-content-mobile" — 스킵 링크가 웹(#main-content)만 타겟팅하므로 모바일에서는 미동작(의도된 허용)

import { TopBar } from "./TopBar";
import { BottomTabBar } from "./BottomTabBar";
import { MobileAppBar } from "./MobileAppBar";
import { HamburgerDrawer } from "./HamburgerDrawer";
import { NotificationModal } from "./NotificationModal";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <>
      {/* [목적] 스킵 네비게이션 — 키보드 사용자가 사이드바 탭 순서를 건너뛰고 본문으로 즉시 이동
          [이유] WCAG 2.1 SC 2.4.1 (AA) — 반복 블록 우회 링크 필수. 시니어/스크린리더 페르소나 C 대응
          [사이드 임팩트] 웹·모바일 양쪽 main에 id="main-content" 존재 — 웹 id 타겟
          [수정 시 고려사항] 모바일 main id="main-content-mobile"이므로 모바일 진입 시 링크가 동작 안 함 (허용 — 모바일은 탭바 구조로 사이드바 없음) */}
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:absolute focus:z-50 focus:left-4 focus:top-4 focus:rounded-lg focus:bg-background focus:px-4 focus:py-2 focus:text-sm focus:font-bold focus:text-foreground focus:outline-none focus:ring-2 focus:ring-ring"
      >
        본문으로 건너뛰기
      </a>

      {/* 웹 레이아웃 (md 이상) */}
      <div className="hidden min-h-screen md:flex md:flex-col">
        <TopBar />
        <NotificationModal />
        <main className="flex-1 overflow-y-auto bg-muted/30 p-8" id="main-content">
          {children}
        </main>
      </div>

      {/* 모바일 레이아웃 (md 미만) */}
      <div className="flex h-dvh flex-col md:hidden">
        <MobileAppBar />
        <main className="flex-1 overflow-y-auto bg-muted/30" id="main-content-mobile">
          {children}
        </main>
        <BottomTabBar />
        <HamburgerDrawer />
      </div>
    </>
  );
}
