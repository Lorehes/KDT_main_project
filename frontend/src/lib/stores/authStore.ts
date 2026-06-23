// [목적] 인증 상태 전역 관리 — 사용자 정보·티어·알림설정·프로필·로그아웃 + 다중 탭 동기화
// [이유] BE GET /users/me 응답의 notify 관련 필드는 플랫 구조(nested 아님) — UserMeResponse 구조 반영.
//   tier_expires_at은 UserMeResponse에 포함됨(null = Free 구독).
//   V22: investment_experience / preferred_time 추가 — null = profile 단계 미입력.
// [사이드 임팩트] Sidebar·TopBar·HamburgerDrawer 등 인증 상태에 의존하는 모든 컴포넌트가 구독.
//   logout()은 BroadcastChannel로 다른 탭에 로그아웃 알림 전파(broadcast.ts 참고).
// [수정 시 고려사항] logout은 LOGOUT_PATH Route Handler 경유(httpOnly 쿠키 삭제 위해).
//   fetchMe 실패 시 user: null — 401 인터셉터가 refresh 시도 후 재호출.
//   다른 탭에서 로그아웃 이벤트 수신은 subscribeAuthBroadcast()를 layout.tsx useEffect에서 구독.
//   investment_experience는 FE 개인화 표시 전용 — 투자 권유 판단에 활용 금지 (통합기획서 §11.1).

import { create } from "zustand";
import { apiClient } from "@/lib/api/client";
import { LOGIN_PATH, LOGOUT_PATH } from "@/lib/constants";
import { broadcastAuth } from "@/lib/auth/broadcast";

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
  // V22 프로필 필드 — null = profile 단계 미입력 (FE 기본값: INTERMEDIATE·REALTIME 표시)
  investment_experience?: "BEGINNER" | "INTERMEDIATE" | "ADVANCED" | null;
  preferred_time?: "REALTIME" | "LUNCH" | "EVENING" | null;
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
      await fetch(LOGOUT_PATH, { method: "POST" });
    } finally {
      set({ user: null });
      broadcastAuth({ type: "logout" }); // 다른 탭에 로그아웃 알림
      if (typeof window !== "undefined") window.location.href = LOGIN_PATH;
    }
  },
}));
