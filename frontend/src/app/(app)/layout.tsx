// [목적] 인증 필요 앱 페이지 공통 레이아웃 — AppShell(웹 Sidebar+TopBar / 모바일 AppBar+BottomTabBar)
// [이유] 대시보드·공시·포트폴리오·알림 등 로그인 후 모든 화면이 동일한 네비게이션 셸을 공유
// [사이드 임팩트] middleware.ts가 이 그룹을 보호 — 미인증 접근 시 /login으로 리다이렉트
// [수정 시 고려사항] 모바일/웹 분기는 AppShell 내부에서 Tailwind md: 브레이크포인트로 처리

import { AppShell } from "@/components/layout/AppShell";
import { ProUpsellModal } from "@/components/domain/ProUpsellModal";
import { AuthBroadcastListener } from "@/components/layout/AuthBroadcastListener";

export default function AppLayout({ children }: { children: React.ReactNode }) {
  return (
    <AppShell>
      <AuthBroadcastListener />
      {children}
      <ProUpsellModal />
    </AppShell>
  );
}
