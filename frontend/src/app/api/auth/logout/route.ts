// [목적] 로그아웃 브리지 — httpOnly 쿠키에서 refresh_token을 읽어 BE /auth/logout 호출 후 쿠키 삭제
// [이유] FE JavaScript는 httpOnly 쿠키를 읽을 수 없으므로 서버 측에서 중계
// [사이드 임팩트] BE refresh token DB 삭제 + 미들웨어가 인식하는 dr_session 쿠키 삭제
// [수정 시 고려사항] BE 호출 실패해도 클라이언트 쿠키는 삭제(사용자 로그아웃 보장)

import { NextRequest, NextResponse } from "next/server";

const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export async function POST(req: NextRequest) {
  const refreshToken = req.cookies.get("dr_refresh")?.value;

  if (refreshToken) {
    // BE refresh token 무효화 (실패해도 클라이언트 쿠키는 삭제)
    await fetch(`${API_URL}/auth/logout`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ refresh_token: refreshToken }),
    }).catch(() => {/* BE 오류 무시 — 클라이언트 쿠키는 반드시 삭제 */});
  }

  const res = NextResponse.json({ ok: true });
  res.cookies.delete("dr_session");
  res.cookies.delete("dr_refresh");
  return res;
}
