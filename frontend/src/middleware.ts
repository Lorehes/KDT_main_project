// [목적] (app) 라우트 그룹 인증 보호 + onboarding_completed 게이트 + 랜딩 로그인 리다이렉트.
//   미인증 보호 라우트 → /login, 인증했지만 온보딩 미완료 → /signup/terms/oauth, 인증 랜딩 → /dashboard.
// [이유] E4 보안 이슈 해소: 기존 미들웨어는 dr_session 쿠키 유무만 체크해 DISCLAIMER 미동의 상태로
//   서비스 전체 접근 가능했음. JWT payload의 onboarding_completed claim으로 온보딩 완료 여부를 추가 검증.
//   JWT 서명 검증은 BE가 담당 — 미들웨어는 라우팅 목적으로만 payload를 디코딩(보안 허용 범위).
//   위조 토큰으로 onboarding_completed=true를 주장해도 실제 API는 BE 서명 검증으로 차단됨.
// [사이드 임팩트] /signup/* 경로 전체를 온보딩 플로우로 취급해 onboarding_completed 체크를 면제.
//   단, /signup/complete는 세션 필수 — 온보딩 완료 API 호출이 필요하므로 별도 가드 처리.
//   기존 PUBLIC_PATHS prefix 매칭 버그 수정: /signup/terms가 PUBLIC이면 /signup/terms/oauth도 통과했던 문제 해소.
//   랜딩(/)은 onboarding_completed=true인 경우에만 /dashboard로 리다이렉트(미완료 사용자는 랜딩 유지).
// [수정 시 고려사항] claim 이름 "onboarding_completed"은 BE JwtTokenProvider.CLAIM_ONBOARDING_COMPLETED와 동기화.
//   새 앱 보호 라우트 추가 시 ONBOARDING_PREFIXES에 포함되지 않으면 자동으로 게이트 적용됨(별도 작업 불필요).
//   새 온보딩 단계 추가 시 /signup/* 하위로 두면 자동 면제됨 (단, 세션 필수 단계는 SIGNUP_PROTECTED_EXACT에 추가).
//   onboarding_completed claim 부재(구 토큰) → false로 명시된 경우만 리다이렉트(backward-compat).

import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";
import { decodeJwtPayload } from "@/lib/auth/jwt-utils";

// 공개 경로 (정확 매칭 전용 — prefix 매칭 의도하지 않음)
const PUBLIC_EXACT = new Set(["/pricing", "/dashboard/preview"]);

// 온보딩/인증 경로 prefix — onboarding_completed 체크 면제
// /signup/* 전체: 가입·약관(이메일/소셜)·인증·프로필 포함
// /login: 비로그인 접근 허용
const ONBOARDING_PREFIXES = ["/signup", "/login"];

// 세션 필수 온보딩 경로 (ONBOARDING_PREFIXES 내 예외 — 비인증 접근 시 /login 리다이렉트)
const SIGNUP_PROTECTED_EXACT = new Set(["/signup/complete"]);

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const session = request.cookies.get("dr_session");

  // ── /_next, /api, 정적 파일 → 무조건 통과 ────────────────────────────────
  if (
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.includes(".")
  ) {
    return NextResponse.next();
  }

  // ── 랜딩(/) 처리 ──────────────────────────────────────────────────────────
  // 온보딩 완료 사용자만 /dashboard SSR 리다이렉트 — 미완료 사용자는 랜딩 유지
  if (pathname === "/") {
    if (session) {
      const claims = decodeJwtPayload(session.value);
      if (claims?.onboarding_completed === true) {
        return NextResponse.redirect(new URL("/dashboard", request.url));
      }
    }
    return NextResponse.next();
  }

  // ── /signup/complete — 세션 필수 온보딩 경로 ─────────────────────────────
  // ONBOARDING_PREFIXES 면제 전에 체크해 세션 없는 접근을 /login으로 리다이렉트
  if (SIGNUP_PROTECTED_EXACT.has(pathname) && !session) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }

  // ── 온보딩/인증 경로 → 무조건 통과 ──────────────────────────────────────
  // /signup/terms/oauth 포함 — 온보딩 중 이 경로에 접근해야 하므로 체크 면제
  if (ONBOARDING_PREFIXES.some((p) => pathname === p || pathname.startsWith(p + "/"))) {
    return NextResponse.next();
  }

  // ── 공개 경로(정확 매칭) → 무조건 통과 ──────────────────────────────────
  if (PUBLIC_EXACT.has(pathname)) return NextResponse.next();

  // ── 보호 경로: 세션 없으면 /login 리다이렉트 ─────────────────────────────
  if (!session) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("redirect", pathname);
    return NextResponse.redirect(loginUrl);
  }

  // ── 보호 경로: 세션 있지만 온보딩 미완료 → /signup/terms/oauth 강제 리다이렉트 ─
  // E4 핵심 방어선: DISCLAIMER 미동의(= 온보딩 미완료) 상태 서비스 전체 접근 차단.
  // backward-compat: claim이 명시적으로 false인 경우만 리다이렉트.
  // claim 부재(onboarding_completed_at 이전 발급 구 토큰)는 통과 → 30분 내 토큰 자연 교체.
  const claims = decodeJwtPayload(session.value);
  if (claims !== null && claims.onboarding_completed === false) {
    return NextResponse.redirect(new URL("/signup/terms/oauth", request.url));
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
