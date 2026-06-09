// [목적] 인증 상태 전역 관리 — 로그인 사용자 정보·티어·로그아웃
// [이유] NextAuth 없이 백엔드 JWT를 httpOnly 쿠키(dr_session)로 관리.
//   클라이언트 메모리에는 사용자 메타(닉네임·티어)만 보관해 보안 리스크 최소화
// [사이드 임팩트] Sidebar·TopBar·HamburgerDrawer 등 인증 상태에 의존하는 모든 컴포넌트가 이 스토어를 구독
// [수정 시 고려사항] 탭 간 세션 동기화가 필요하면 BroadcastChannel 또는 storage 이벤트 추가.
//   소셜 OAuth 콜백 후 /api/auth/callback에서 setUser 호출 필요

import { create } from "zustand";
import { apiClient } from "@/lib/api/client";

export interface AuthUser {
  id: number;
  email: string;
  nickname: string;
  tier: "FREE" | "PRO" | "PREMIUM";
  tier_expires_at: string | null;
  phone_verified: boolean;
}

interface AuthState {
  user: AuthUser | null;
  isLoading: boolean;
  setUser: (user: AuthUser | null) => void;
  fetchMe: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useAuthStore = create<AuthState>((set) => ({
  user: null,
  isLoading: false,

  setUser: (user) => set({ user }),

  fetchMe: async () => {
    set({ isLoading: true });
    try {
      const user = await apiClient<AuthUser>("/users/me");
      set({ user });
    } catch {
      set({ user: null });
    } finally {
      set({ isLoading: false });
    }
  },

  logout: async () => {
    try {
      await apiClient("/auth/logout", { method: "POST" });
    } finally {
      set({ user: null });
      window.location.href = "/login";
    }
  },
}));
