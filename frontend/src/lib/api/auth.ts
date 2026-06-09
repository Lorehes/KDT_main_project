// [목적] 인증 API 타입 + mutation 함수 (signup, login, oauth url, phone verify)
// [이유] TanStack Query mutation으로 폼 제출과 서버 통신을 연결
// [사이드 임팩트] 성공 시 authStore.setUser 호출. httpOnly cookie는 서버가 Set-Cookie로 설정
// [수정 시 고려사항] OAuth 콜백은 Next.js API route(/api/auth/callback/kakao 등)에서 처리.
//   현재 미구현 — W3 온보딩 구현 시 추가

import { useMutation } from "@tanstack/react-query";
import { apiClient } from "./client";
import { useAuthStore, type AuthUser } from "@/lib/stores/authStore";

export type ConsentType = "TERMS" | "PRIVACY" | "MARKETING" | "DISCLAIMER";

export interface ConsentItem {
  consent_type: ConsentType;
  agreed: boolean;
  policy_version: string;
}

export interface SignupBody {
  email: string;
  password: string;
  nickname?: string;
  consents: ConsentItem[];
}

export interface LoginBody {
  email: string;
  password: string;
}

export interface AuthResponse {
  user: AuthUser;
}

export function useSignup() {
  const { setUser } = useAuthStore();
  return useMutation({
    mutationFn: (body: SignupBody) =>
      apiClient<AuthResponse>("/auth/signup", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (data) => setUser(data.user),
  });
}

export function useLogin() {
  const { setUser } = useAuthStore();
  return useMutation({
    mutationFn: (body: LoginBody) =>
      apiClient<AuthResponse>("/auth/login", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: (data) => setUser(data.user),
  });
}

export function useOAuthUrl(provider: "kakao" | "google" | "naver") {
  return async () => apiClient<{ url: string }>(`/auth/oauth/${provider}/url`);
}
