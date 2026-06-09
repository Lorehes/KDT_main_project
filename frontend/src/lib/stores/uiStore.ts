// [목적] UI 전역 상태 — 드로어 열림·알림 모달·업셀 모달 제어
// [이유] 서버 상태(TanStack Query)와 분리된 순수 UI 상태를 Zustand로 관리
// [사이드 임팩트] HamburgerDrawer·TopBar·ProUpsellModal이 이 스토어를 구독
// [수정 시 고려사항] 모달 타입이 늘어나면 modalType: string | null 패턴으로 확장 가능

import { create } from "zustand";

interface UIState {
  drawerOpen: boolean;
  notifModalOpen: boolean;
  upsellModalOpen: boolean;
  setDrawerOpen: (open: boolean) => void;
  toggleNotifModal: () => void;
  setUpsellModalOpen: (open: boolean) => void;
}

export const useUIStore = create<UIState>((set) => ({
  drawerOpen: false,
  notifModalOpen: false,
  upsellModalOpen: false,

  setDrawerOpen: (open) => set({ drawerOpen: open }),
  toggleNotifModal: () => set((s) => ({ notifModalOpen: !s.notifModalOpen })),
  setUpsellModalOpen: (open) => set({ upsellModalOpen: open }),
}));
