// [목적] OAuth 콜백 Route Handler — provider(kakao/google)가 리다이렉트한 code+state를 수신해 BE 토큰 교환 후 쿠키 저장.
// [이유] Next.js 서버사이드에서 처리해야 httpOnly 쿠키를 안전하게 기록 가능.
//   BE POST /auth/oauth/{provider}/callback 응답 = AuthResponse(access_token, refresh_token, is_new_user).
//   쿠키 TTL은 /api/auth/session/route.ts 와 동일 값 유지 (30분 access / 14일 refresh).
// [사이드 임팩트] code 또는 state 미존재 → CSRF 가능성으로 즉시 /login?error=oauth_failed.
//   BE 오류(400/409/500 등) → /login?error=oauth_failed.
//   is_new_user=true(신규 가입 또는 약관 동의 미완료) → 토큰 쿠키 저장 후 /signup/terms?oauth=true 리다이렉트.
//   is_new_user=false(기존 사용자) → /dashboard 리다이렉트.
// [수정 시 고려사항] 네이버 provider 추가 시 이 핸들러는 자동 지원(동적 라우트 [provider]).
//   TTL 변경 시 /api/auth/session/route.ts 와 동시에 수정 필요.

import { NextRequest, NextResponse } from "next/server";

// NEXT_PUBLIC_API_URL = "http://localhost:8080/api/v1" — 서버 라우트에서도 접근 가능 (빌드 타임 임베드)
const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";
const ACCESS_TTL_SECONDS  = 30 * 60;          // 30분 — /api/auth/session/route.ts 와 동기화
const REFRESH_TTL_SECONDS = 14 * 24 * 3600;   // 14일

const ALLOWED_PROVIDERS = ["kakao", "google"] as const;
type AllowedProvider = (typeof ALLOWED_PROVIDERS)[number];

function isAllowedProvider(p: string): p is AllowedProvider {
  return (ALLOWED_PROVIDERS as readonly string[]).includes(p);
}

// [목적] 리다이렉트 절대 URL의 공개 origin 산출.
// [이유] Next standalone이 프록시 뒤에서 req.url을 내부 바인딩(0.0.0.0:3000)으로 해석 →
//   new URL(path, req.url)이 컨테이너 주소를 만들어 브라우저 연결 거부. nginx가 전달하는
//   Host/X-Forwarded-Proto 헤더로 공개 origin(https://gangwoncanvas.co.kr)을 구성한다.
function publicOrigin(req: NextRequest): string {
  const proto = req.headers.get("x-forwarded-proto") ?? "https";
  const host = req.headers.get("host") ?? req.nextUrl.host;
  return `${proto}://${host}`;
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
    return NextResponse.redirect(new URL("/login?error=oauth_failed", publicOrigin(req)));
  }

  let callbackRes: Response;
  try {
    callbackRes = await fetch(`${BASE_URL}/auth/oauth/${provider}/callback`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ code, state }),
    });
  } catch {
    return NextResponse.redirect(new URL("/login?error=oauth_failed", publicOrigin(req)));
  }

  if (!callbackRes.ok) {
    return NextResponse.redirect(new URL("/login?error=oauth_failed", publicOrigin(req)));
  }

  const tokens: { access_token: string; refresh_token: string; is_new_user: boolean } =
    await callbackRes.json();

  // is_new_user=true: 신규 가입 또는 온보딩 미완료 → 소셜 전용 약관 동의 화면으로 유도
  // /signup/terms?oauth=true URL 파라미터 의존 제거(M-S1) — 독립 경로 /signup/terms/oauth 사용
  const isProd = process.env.NODE_ENV === "production";
  const redirectPath = tokens.is_new_user ? "/signup/terms/oauth" : "/dashboard";
  const redirect = NextResponse.redirect(new URL(redirectPath, publicOrigin(req)));

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
