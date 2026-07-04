// [목적] BE가 JSON body로 반환한 access/refresh token을 httpOnly 쿠키로 변환하는 브리지 Route Handler
// [이유] BE는 Set-Cookie를 발급하지 않고 JSON body로 토큰을 반환함(기존 구현 유지).
//   Next.js 미들웨어는 서버 측에서 쿠키를 읽어야 하므로 이 Route Handler가 httpOnly 쿠키를 기록함.
// [사이드 임팩트] FE login/signup mutation 성공 후 이 라우트를 호출해야 미들웨어가 세션을 인식함
// [수정 시 고려사항] BE가 Set-Cookie를 직접 발급하도록 변경 시 이 Route Handler는 제거 가능.
//   sameSite:"lax"는 CSRF 방어. HTTPS 프로덕션에서는 secure:true 추가 필요

import { NextRequest, NextResponse } from "next/server";

const ACCESS_TTL_SECONDS = 30 * 60;   // 30분 (BE access token TTL과 동기화)
const REFRESH_TTL_SECONDS = 14 * 24 * 3600; // 14일

export async function POST(req: NextRequest) {
  const { access_token, refresh_token } = await req.json();

  if (!access_token || !refresh_token) {
    return NextResponse.json({ error: "토큰이 없습니다" }, { status: 400 });
  }

  const res = NextResponse.json({ ok: true });
  const isProd = process.env.NODE_ENV === "production";

  res.cookies.set("dr_session", access_token, {
    httpOnly: true,
    sameSite: "lax",
    secure: isProd,
    path: "/",
    maxAge: ACCESS_TTL_SECONDS,
  });

  res.cookies.set("dr_refresh", refresh_token, {
    httpOnly: true,
    sameSite: "lax",
    secure: isProd,
    path: "/api/auth",  // refresh 전용 경로로 제한
    maxAge: REFRESH_TTL_SECONDS,
  });

  return res;
}
