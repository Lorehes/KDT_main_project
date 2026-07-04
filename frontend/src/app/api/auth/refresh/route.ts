// [목적] access token 갱신 브리지 — httpOnly 쿠키의 refresh_token으로 BE /auth/refresh 호출 후 쿠키 갱신
// [이유] FE JavaScript는 httpOnly 쿠키를 읽을 수 없으므로 서버 측 Route Handler가 refresh를 중계
// [사이드 임팩트] client.ts 401 인터셉터가 이 라우트를 호출. 갱신 성공 시 원 요청을 재시도
// [수정 시 고려사항] refresh 자체가 401이면 쿠키를 삭제하고 /login으로 유도

import { NextRequest, NextResponse } from "next/server";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export async function POST(req: NextRequest) {
  const refreshToken = req.cookies.get("dr_refresh")?.value;

  if (!refreshToken) {
    return NextResponse.json({ error: "refresh token 없음" }, { status: 401 });
  }

  const beRes = await fetch(`${API_URL}/auth/refresh`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ refresh_token: refreshToken }),
  });

  if (!beRes.ok) {
    // refresh 실패 → 쿠키 삭제
    const res = NextResponse.json({ error: "refresh 실패" }, { status: 401 });
    res.cookies.delete("dr_session");
    res.cookies.delete("dr_refresh");
    return res;
  }

  const { access_token, refresh_token } = await beRes.json();
  const isProd = process.env.NODE_ENV === "production";
  const res = NextResponse.json({ ok: true });

  res.cookies.set("dr_session", access_token, {
    httpOnly: true, sameSite: "lax", secure: isProd, path: "/", maxAge: 30 * 60,
  });
  res.cookies.set("dr_refresh", refresh_token, {
    httpOnly: true, sameSite: "lax", secure: isProd, path: "/api/auth", maxAge: 14 * 24 * 3600,
  });

  return res;
}
