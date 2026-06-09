// [목적] 인증 상태 전역 관리 — 사용자 정보·티어·알림설정·로그아웃
// [이유] BE GET /users/me 응답의 notify 관련 필드는 플랫 구조(nested 아님) — UserMeResponse 구조 반영.
//   tier_expires_at은 UserMeResponse에 포함됨(null = Free 구독).
// [사이드 임팩트] Sidebar·TopBar·HamburgerDrawer 등 인증 상태에 의존하는 모든 컴포넌트가 구독
// [수정 시 고려사항] logout은 /api/auth/logout Route Handler 경유(httpOnly 쿠키 삭제 위해).
//   fetchMe 실패 시 user: null — 401 인터셉터가 refresh 시도 후 재호출

import { create } from "zustand";
import { apiClient } from "@/lib/api/client";

export interface AuthUser {
  id: number;
  email: string;
  nickname: string;
  tier: "FREE" | "PRO" | "PREMIUM";
  tier_expires_at: string | null;
  // BE UserMeResponse 플랫 notify 필드 (nested 아님, notify_enabled = 계정 전역 알림 on/off)
  notify_channel?: "KAKAO" | "TELEGRAM" | "EMAIL";
  notify_enabled?: boolean;
  notify_frequency?: "INSTANT" | "DAILY_1" | "DAILY_2" | "WEEKLY";
  notify_type_filter?: "POSITIVE_ONLY" | "NEGATIVE_ONLY" | "ALL";
  off_hours_allowed?: boolean;
  terms_agreed_at?: string;
  privacy_agreed_at?: string;
  marketing_agreed_at?: string | null;
  created_at?: string;
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
      // httpOnly 쿠키(dr_refresh) 읽기는 서버 전용 → Route Handler 경유
      await fetch("/api/auth/logout", { method: "POST" });
    } finally {
      set({ user: null });
      window.location.href = "/login";
    }
  },
}));
