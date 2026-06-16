// [목적] 인증·사용자 API 타입 + TanStack Query 훅 (signup, login, oauth, me, logout, phone OTP, consent)
// [이유] BE SignupRequest는 flat boolean 동의 필드 구조. api_spec 명세는 배열이나 BE 구현이 우선.
//   login/signup 성공 후 /api/auth/session Route Handler로 httpOnly 쿠키를 기록해 미들웨어 세션 연동
//   initiateOAuth: getOAuthUrl()로 도메인 검증된 URL 취득 → window.location.href 전체 리다이렉트(카카오 팝업 불가 정책).
// [사이드 임팩트] useSignup/useLogin 모두 성공 시 authStore.setUser + /api/auth/session 쿠키 기록.
//   useSendPhoneOtp → BE Caffeine rate limit(1분 1회, 시간당 5회). useConfirmPhoneOtp 성공 시 fetchMe()로 phone_verified 갱신.
//   initiateOAuth: 호출 즉시 페이지 이동 — 이후 콜백은 /api/auth/callback/[provider]/route.ts 에서 처리.
// [수정 시 고려사항] BE가 Set-Cookie를 직접 발급하도록 변경 시 /api/auth/session 호출 제거.
//   BE UpdateMeRequest는 nickname 단일 필드(@NotBlank) — investment_experience/preferred_time은 BE 미지원으로 제거됨

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiClient } from "./client";
import { useAuthStore, type AuthUser } from "@/lib/stores/authStore";
import { LOGIN_PATH, LOGOUT_PATH, SESSION_PATH } from "@/lib/constants";

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
  const res = await fetch(SESSION_PATH, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
    }),
  });
  if (!res.ok) {
    throw new Error(`[storeTokenCookies] 쿠키 설정 실패: ${res.status}`);
  }
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
      await fetch(LOGOUT_PATH, { method: "POST" });
    },
    onSuccess: () => {
      setUser(null);
      if (typeof window !== "undefined") window.location.href = LOGIN_PATH;
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

/** OTP 발송 — POST /users/me/phone/verify. 429 RATE_LIMIT_EXCEEDED 시 오류 toast 필요. */
export function useSendPhoneOtp() {
  return useMutation({
    mutationFn: (phoneNumber: string) =>
      apiClient<void>("/users/me/phone/verify", {
        method: "POST",
        body: JSON.stringify({ phone_number: phoneNumber }),
      }),
  });
}

/** OTP 검증 — POST /users/me/phone/verify/confirm. 성공 시 fetchMe()로 phone_verified 갱신. */
export function useConfirmPhoneOtp() {
  const { fetchMe } = useAuthStore();
  return useMutation({
    mutationFn: (code: string) =>
      apiClient<void>("/users/me/phone/verify/confirm", {
        method: "POST",
        body: JSON.stringify({ code }),
      }),
    onSuccess: async () => {
      await fetchMe();
    },
  });
}

// ─── 이메일 OTP ─────────────────────────────────────────────────────────────

/** OTP 발송 — POST /auth/email/send-otp. 이미 가입된 이메일이면 409, rate limit 초과 시 429. */
export function useSendEmailOtp() {
  return useMutation({
    mutationFn: (email: string) =>
      apiClient<void>("/auth/email/send-otp", {
        method: "POST",
        body: JSON.stringify({ email }),
      }),
  });
}

/** OTP 검증 — POST /auth/email/verify. 만료 시 410, 불일치 시 400, 성공 시 204. */
export function useVerifyEmailOtp() {
  return useMutation({
    mutationFn: ({ email, code }: { email: string; code: string }) =>
      apiClient<void>("/auth/email/verify", {
        method: "POST",
        body: JSON.stringify({ email, code }),
      }),
  });
}

// ─── 소셜 약관 동의 ──────────────────────────────────────────────────────────

export interface OAuthConsentBody {
  termsAgreed: boolean;
  privacyAgreed: boolean;
  disclaimerAgreed: boolean;
  marketingAgreed: boolean;
}

/**
 * 소셜 로그인 신규 가입 후 약관 동의 저장 — POST /users/me/oauth-consent.
 * /signup/terms?oauth=true 화면에서 호출. 인증 쿠키(dr_session) 필요.
 * 204 No Content 반환 → onSuccess에서 router.push("/signup/phone").
 */
export function useOAuthConsent() {
  return useMutation({
    mutationFn: (body: OAuthConsentBody) =>
      apiClient<void>("/users/me/oauth-consent", {
        method: "POST",
        body: JSON.stringify(body),
      }),
  });
}

/** R8: 허용된 OAuth 제공자 도메인 — BE 취약점 또는 MITM으로 악의적 URL이 반환될 경우 차단 */
const ALLOWED_OAUTH_DOMAINS = [
  "https://kauth.kakao.com",
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

/**
 * OAuth 인가 시작 — URL 취득 후 전체 페이지 리다이렉트.
 * 카카오는 팝업 불가(카카오 정책) → window.location.href 사용.
 * URL은 getOAuthUrl() 에서 ALLOWED_OAUTH_DOMAINS 화이트리스트 검증 완료.
 */
export async function initiateOAuth(provider: "kakao" | "google"): Promise<void> {
  const url = await getOAuthUrl(provider);
  window.location.href = url;
}
