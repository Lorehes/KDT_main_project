"use client";

// [목적] 인증 앱 전체 셸 — 웹(Sidebar+TopBar) / 모바일(AppBar+BottomTabBar)를 단일 컴포넌트로 제공
// [이유] md 브레이크포인트 하나로 웹·모바일 레이아웃을 전환해 중복 라우트·layout 없이 반응형 처리
// [사이드 임팩트] (app)/layout.tsx에서 래핑. 하위 모든 page가 이 셸을 상속
// [수정 시 고려사항] 웹: min-h-screen flex. 모바일: flex-col h-dvh overflow-hidden.
//   스크롤 영역은 메인 컨텐츠 영역만(overflow-y-auto). 탭바·사이드바는 고정

import { Sidebar } from "./Sidebar";
import { TopBar } from "./TopBar";
import { BottomTabBar } from "./BottomTabBar";
import { MobileAppBar } from "./MobileAppBar";
import { HamburgerDrawer } from "./HamburgerDrawer";
import { NotificationModal } from "./NotificationModal";

export function AppShell({ children }: { children: React.ReactNode }) {
  return (
    <>
      {/* 웹 레이아웃 (md 이상) */}
      <div className="hidden min-h-screen md:flex">
        <Sidebar />
        <div className="relative flex flex-1 flex-col overflow-hidden">
          <TopBar />
          <NotificationModal />
          <main className="flex-1 overflow-y-auto bg-muted/30 p-8" id="main-content">
            {children}
          </main>
        </div>
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
