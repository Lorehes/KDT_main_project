// [목적] OAuth 콜백 Route Handler — provider(kakao/google)가 리다이렉트한 code+state를 수신해 BE 토큰 교환 후 쿠키 저장.
// [이유] Next.js 서버사이드에서 처리해야 httpOnly 쿠키를 안전하게 기록 가능.
//   BE POST /auth/oauth/{provider}/callback 응답 = AuthResponse(access_token, refresh_token).
//   쿠키 TTL은 /api/auth/session/route.ts 와 동일 값 유지 (30분 access / 14일 refresh).
// [사이드 임팩트] code 또는 state 미존재 → CSRF 가능성으로 즉시 /login?error=oauth_failed.
//   BE 오류(400/409/500 등) → /login?error=oauth_failed.
//   BE autoSignup은 소셜 신규 가입 시 약관 동의를 자동 처리(AuthService.autoSignup 참고) — 별도 /signup/terms 이동 불필요.
// [수정 시 고려사항] 네이버 provider 추가 시 이 핸들러는 자동 지원(동적 라우트 [provider]).
//   TTL 변경 시 /api/auth/session/route.ts 와 동시에 수정 필요.

import { NextRequest, NextResponse } from "next/server";

const API_URL = process.env.API_URL ?? "http://localhost:8080";
const ACCESS_TTL_SECONDS  = 30 * 60;          // 30분 — /api/auth/session/route.ts 와 동기화
const REFRESH_TTL_SECONDS = 14 * 24 * 3600;   // 14일

const ALLOWED_PROVIDERS = ["kakao", "google"] as const;
type AllowedProvider = (typeof ALLOWED_PROVIDERS)[number];

function isAllowedProvider(p: string): p is AllowedProvider {
  return (ALLOWED_PROVIDERS as readonly string[]).includes(p);
}

export async function GET(
  req: NextRequest,
  { params }: { params: Promise<{ provider: string }> }
) {
  const { provider } = await params;
  const code  = req.nextUrl.searchParams.get("code");
  const state = req.nextUrl.searchParams.get("state");

  // code·state 미존재 → CSRF/잘못된 요청 방어
  if (!code || !state || !isAllowedProvider(provider)) {
    return NextResponse.redirect(new URL("/login?error=oauth_failed", req.url));
  }

  let callbackRes: Response;
  try {
    callbackRes = await fetch(`${API_URL}/api/v1/auth/oauth/${provider}/callback`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, state }),
    });
  } catch {
    return NextResponse.redirect(new URL("/login?error=oauth_failed", req.url));
  }

  if (!callbackRes.ok) {
    return NextResponse.redirect(new URL("/login?error=oauth_failed", req.url));
  }

  const tokens: { access_token: string; refresh_token: string } = await callbackRes.json();

  // httpOnly 쿠키를 리다이렉트 응답에 직접 설정 (self-fetch 제거 — edge runtime 호환)
  const isProd = process.env.NODE_ENV === "production";
  const redirect = NextResponse.redirect(new URL("/dashboard", req.url));

  redirect.cookies.set("dr_session", tokens.access_token, {
    httpOnly: true,
    sameSite: "lax",
    secure: isProd,
    path: "/",
    maxAge: ACCESS_TTL_SECONDS,
  });
  redirect.cookies.set("dr_refresh", tokens.refresh_token, {
    httpOnly: true,
    sameSite: "lax",
    secure: isProd,
    path: "/api/auth",
    maxAge: REFRESH_TTL_SECONDS,
  });

  return redirect;
}
