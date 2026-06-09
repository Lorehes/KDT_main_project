// [목적] (app) 라우트 그룹 인증 보호 — 미인증 요청을 /login으로 리다이렉트
// [이유] 서버 레벨에서 인증을 차단해 클라이언트 플리커(미인증 상태의 앱 셸 순간 노출) 방지
// [사이드 임팩트] (public)·(auth)·/_next·/api 경로는 통과. 새 보호 라우트 추가 시 matcher 갱신 필요
// [수정 시 고려사항] Zustand authStore의 토큰은 클라이언트 메모리에만 존재하므로
//   서버 측 검사는 httpOnly 쿠키 'dr_session' 유무로만 판단. 실제 유효성은 API가 검증.

import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

const PUBLIC_PATHS = ["/", "/pricing", "/login", "/signup"];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;

  const isPublic =
    PUBLIC_PATHS.some((p) => pathname === p || pathname.startsWith(p + "/")) ||
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.includes(".");

  if (isPublic) return NextResponse.next();

  const session = request.cookies.get("dr_session");
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
