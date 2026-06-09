// [목적] 인증·사용자 API 타입 + TanStack Query 훅 (signup, login, oauth, me, logout, phone)
// [이유] BE SignupRequest는 flat boolean 동의 필드 구조. api_spec 명세는 배열이나 BE 구현이 우선.
//   login/signup 성공 후 /api/auth/session Route Handler로 httpOnly 쿠키를 기록해 미들웨어 세션 연동
// [사이드 임팩트] useSignup/useLogin 모두 성공 시 authStore.setUser + /api/auth/session 쿠키 기록
// [수정 시 고려사항] BE가 Set-Cookie를 직접 발급하도록 변경 시 /api/auth/session 호출 제거.
//   BE UpdateMeRequest는 nickname 단일 필드(@NotBlank) — investment_experience/preferred_time은 BE 미지원으로 제거됨

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import { useAuthStore, type AuthUser } from "@/lib/stores/authStore";

// ─── 타입 ───────────────────────────────────────────────────────────────────

/** BE SignupRequest 필드명과 1:1 대응 (flat boolean 구조) */
export interface SignupBody {
  email: string;
  password: string;
  nickname: string;
  termsAgreed: boolean;
  privacyAgreed: boolean;
  disclaimerAgreed: boolean;
  marketingAgreed: boolean;
}

export interface LoginBody {
  email: string;
  password: string;
}

export interface AuthTokenResponse {
  access_token: string;
  refresh_token: string;
  token_type: string;
  expires_in: number;
}

export interface UpdateMeBody {
  nickname: string;  // BE UpdateMeRequest @NotBlank — 필수. 향후 필드 확장 시 BE UpdateMeRequest 동시 수정
}

// ─── 헬퍼 — 토큰 → httpOnly 쿠키 저장 ──────────────────────────────────────

async function storeTokenCookies(tokens: AuthTokenResponse): Promise<void> {
  await fetch("/api/auth/session", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
    }),
  });
}

// ─── 훅 ─────────────────────────────────────────────────────────────────────

export function useSignup() {
  const { fetchMe } = useAuthStore();
  return useMutation({
    mutationFn: (body: SignupBody) =>
      apiClient<AuthTokenResponse>("/auth/signup", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: async (tokens) => {
      await storeTokenCookies(tokens);
      await fetchMe();
    },
  });
}

export function useLogin() {
  const { fetchMe } = useAuthStore();
  return useMutation({
    mutationFn: (body: LoginBody) =>
      apiClient<AuthTokenResponse>("/auth/login", { method: "POST", body: JSON.stringify(body) }),
    onSuccess: async (tokens) => {
      await storeTokenCookies(tokens);
      await fetchMe();
    },
  });
}

export function useLogout() {
  const { setUser } = useAuthStore();
  return useMutation({
    mutationFn: async () => {
      await fetch("/api/auth/logout", { method: "POST" });
    },
    onSuccess: () => {
      setUser(null);
      window.location.href = "/login";
    },
  });
}

export function useMe() {
  return useQuery({
    queryKey: ["me"],
    queryFn: () => apiClient<AuthUser>("/users/me"),
    staleTime: 60_000,
  });
}

export function useUpdateMe() {
  const qc = useQueryClient();
  const { fetchMe } = useAuthStore();
  return useMutation({
    mutationFn: (body: UpdateMeBody) =>
      apiClient<AuthUser>("/users/me", { method: "PATCH", body: JSON.stringify(body) }),
    onSuccess: async () => {
      await fetchMe();
      qc.invalidateQueries({ queryKey: ["me"] });
    },
  });
}

export function usePhoneVerify() {
  return useMutation({
    mutationFn: (body: { phone: string; code: string }) =>
      apiClient("/users/me/phone/verify", { method: "POST", body: JSON.stringify(body) }),
  });
}

/** R8: 허용된 OAuth 제공자 도메인 — BE 취약점 또는 MITM으로 악의적 URL이 반환될 경우 차단 */
const ALLOWED_OAUTH_DOMAINS = [
  "https://accounts.kakao.com",
  "https://accounts.google.com",
  "https://nid.naver.com",
] as const;

/** OAuth 인가 URL 취득 — 반환 URL이 허용 도메인인지 검증(Open Redirect 방지) */
export async function getOAuthUrl(provider: "kakao" | "google" | "naver"): Promise<string> {
  const { url } = await apiClient<{ url: string; state: string }>(`/auth/oauth/${provider}/url`);
  if (!ALLOWED_OAUTH_DOMAINS.some((domain) => url.startsWith(domain))) {
    throw new Error(`OAuth URL이 허용되지 않은 도메인입니다. provider=${provider}`);
  }
  return url;
}
