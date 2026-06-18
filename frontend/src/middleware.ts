// [목적] (app) 라우트 그룹 인증 보호 + 랜딩 로그인 리다이렉트 — 미인증 보호 라우트→/login, 인증 랜딩→/dashboard
// [이유] 서버 레벨에서 인증을 차단해 클라이언트 플리커(미인증 상태의 앱 셸 순간 노출) 방지.
//   랜딩(/) 클라이언트 fetchMe는 비로그인 시 401+refresh 2건의 콘솔 에러 유발 → SSR 리다이렉트로 교체
// [사이드 임팩트] (public)·(auth)·/_next·/api 경로는 통과. 새 보호 라우트 추가 시 matcher 갱신 필요
// [수정 시 고려사항] Zustand authStore의 토큰은 클라이언트 메모리에만 존재하므로
//   서버 측 검사는 httpOnly 쿠키 'dr_session' 유무로만 판단. 실제 유효성은 API가 검증.
//   dr_session이 있어도 만료됐으면 /dashboard → 미들웨어가 /login으로 다시 리다이렉트 (이중 리다이렉트 허용).
//   /signup/complete는 가입 완료 후(세션 존재) 진입하므로 보호 라우트 — PUBLIC_PATHS에서 제외.
//   온보딩 각 단계(/signup/verify~profile)는 세션 없이도 접근 가능해야 하므로 명시 열거.

import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const PUBLIC_PATHS = [
  "/",
  "/pricing",
  "/login",
  "/signup",
  "/signup/verify",
  "/signup/terms",
  "/signup/phone",
  "/signup/profile",
  "/dashboard/preview",
];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const session = request.cookies.get("dr_session");

  // 랜딩: 세션 있으면 /dashboard SSR 리다이렉트 (LandingRedirect 클라이언트 fetchMe 제거로 인한 콘솔 에러 방지)
  if (pathname === "/") {
    if (session) return NextResponse.redirect(new URL("/dashboard", request.url));
    return NextResponse.next();
  }

  const isPublic =
    PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(p + "/")) ||
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.includes(".");

  if (isPublic) return NextResponse.next();

  if (!session) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
