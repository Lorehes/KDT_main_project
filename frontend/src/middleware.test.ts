import { describe, it, expect, vi, beforeEach } from "vitest";
import type { NextRequest } from "next/server";

// ── next/server mock ──────────────────────────────────────────────────────────
// middleware.ts는 Edge Runtime 전용 next/server를 사용.
// 라우팅 결과(redirect URL / next 통과)를 검증하기 위해 최소 mock 구현.
const redirectCalls: URL[] = [];
const nextCalls: number[] = [];

vi.mock("next/server", () => ({
  NextResponse: {
    next: () => { nextCalls.push(1); return { type: "next" }; },
    redirect: (url: URL) => { redirectCalls.push(url); return { type: "redirect", url }; },
  },
}));

// ── jwt-utils mock — 실제 구현 사용 (no mock) ────────────────────────────────
// jwt-utils.ts는 순수 함수이므로 mock 없이 실제 구현 사용.
// middleware.ts가 "@/lib/auth/jwt-utils"를 import하므로 alias 해소 필요.
vi.mock("@/lib/auth/jwt-utils", async () => {
  const actual = await vi.importActual<typeof import("./lib/auth/jwt-utils")>("./lib/auth/jwt-utils");
  return actual;
});

import { middleware } from "./middleware";

// ── 테스트용 NextRequest mock 빌더 ───────────────────────────────────────────
function makeToken(payload: object): string {
  const encode = (obj: object) =>
    btoa(JSON.stringify(obj)).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
  return `${encode({ alg: "HS256" })}.${encode(payload)}.fake`;
}

function makeRequest(pathname: string, cookieOverrides: Record<string, string> = {}): NextRequest {
  const url = `http://localhost${pathname}`;
  const cookieMap = new Map(Object.entries(cookieOverrides));
  return {
    nextUrl: { pathname },
    url,
    cookies: {
      get: (name: string) => cookieMap.has(name) ? { name, value: cookieMap.get(name)! } : undefined,
    },
  } as unknown as NextRequest;
}

// ── 테스트 케이스 ─────────────────────────────────────────────────────────────
describe("middleware — 인증·온보딩 게이트", () => {
  beforeEach(() => {
    redirectCalls.length = 0;
    nextCalls.length = 0;
  });

  // ─── 정적/API 경로 무조건 통과 ─────────────────────────────────────────────
  it("/_next 경로는 세션 없이도 통과한다", () => {
    middleware(makeRequest("/_next/static/chunk.js"));
    expect(nextCalls).toHaveLength(1);
    expect(redirectCalls).toHaveLength(0);
  });

  it("/api/* 경로는 통과한다", () => {
    middleware(makeRequest("/api/auth/session"));
    expect(nextCalls).toHaveLength(1);
  });

  it(".ico 등 확장자 포함 경로는 통과한다", () => {
    middleware(makeRequest("/favicon.ico"));
    expect(nextCalls).toHaveLength(1);
  });

  // ─── 랜딩(/) ───────────────────────────────────────────────────────────────
  it("/ + 세션 없음 → 통과 (랜딩 페이지 표시)", () => {
    middleware(makeRequest("/"));
    expect(nextCalls).toHaveLength(1);
  });

  it("/ + 세션 있고 onboarding_completed=true → /dashboard 리다이렉트", () => {
    const token = makeToken({ onboarding_completed: true });
    middleware(makeRequest("/", { dr_session: token }));
    expect(redirectCalls[0]?.pathname).toBe("/dashboard");
  });

  it("/ + 세션 있고 onboarding_completed=false → 랜딩 유지(통과)", () => {
    const token = makeToken({ onboarding_completed: false });
    middleware(makeRequest("/", { dr_session: token }));
    expect(nextCalls).toHaveLength(1);
    expect(redirectCalls).toHaveLength(0);
  });

  it("/ + 구 토큰(claim 없음) → 랜딩 유지(통과)", () => {
    const token = makeToken({ sub: "1", tier: "FREE" }); // onboarding_completed 없음
    middleware(makeRequest("/", { dr_session: token }));
    expect(nextCalls).toHaveLength(1);
  });

  // ─── 온보딩/인증 경로 면제 ──────────────────────────────────────────────────
  it("/signup/* 경로는 세션 없이도 통과한다", () => {
    middleware(makeRequest("/signup/terms/oauth"));
    expect(nextCalls).toHaveLength(1);
  });

  it("/signup/terms 이메일 약관 페이지는 통과한다", () => {
    middleware(makeRequest("/signup/terms"));
    expect(nextCalls).toHaveLength(1);
  });

  it("/login 경로는 통과한다", () => {
    middleware(makeRequest("/login"));
    expect(nextCalls).toHaveLength(1);
  });

  // ─── /signup/complete 세션 가드 (M-2 수정) ─────────────────────────────────
  it("/signup/complete + 세션 없음 → /login?redirect=/signup/complete 리다이렉트", () => {
    middleware(makeRequest("/signup/complete"));
    expect(redirectCalls[0]?.pathname).toBe("/login");
    expect(redirectCalls[0]?.searchParams.get("redirect")).toBe("/signup/complete");
  });

  it("/signup/complete + 세션 있음 → 통과", () => {
    const token = makeToken({ onboarding_completed: false });
    middleware(makeRequest("/signup/complete", { dr_session: token }));
    expect(nextCalls).toHaveLength(1);
    expect(redirectCalls).toHaveLength(0);
  });

  // ─── 공개 경로 (정확 매칭) ─────────────────────────────────────────────────
  it("/pricing → 통과", () => {
    middleware(makeRequest("/pricing"));
    expect(nextCalls).toHaveLength(1);
  });

  it("/dashboard/preview → 통과", () => {
    middleware(makeRequest("/dashboard/preview"));
    expect(nextCalls).toHaveLength(1);
  });

  // ─── 보호 경로 + 미인증 ─────────────────────────────────────────────────────
  it("/dashboard + 세션 없음 → /login?redirect=/dashboard 리다이렉트", () => {
    middleware(makeRequest("/dashboard"));
    expect(redirectCalls[0]?.pathname).toBe("/login");
    expect(redirectCalls[0]?.searchParams.get("redirect")).toBe("/dashboard");
  });

  it("/portfolios + 세션 없음 → /login 리다이렉트", () => {
    middleware(makeRequest("/portfolios"));
    expect(redirectCalls[0]?.pathname).toBe("/login");
  });

  // ─── E4 핵심 게이트: 온보딩 미완료 차단 ───────────────────────────────────
  it("/dashboard + onboarding_completed=false → /signup/terms/oauth 리다이렉트", () => {
    const token = makeToken({ onboarding_completed: false });
    middleware(makeRequest("/dashboard", { dr_session: token }));
    expect(redirectCalls[0]?.pathname).toBe("/signup/terms/oauth");
  });

  it("/disclosures + onboarding_completed=false → /signup/terms/oauth 리다이렉트", () => {
    const token = makeToken({ onboarding_completed: false });
    middleware(makeRequest("/disclosures", { dr_session: token }));
    expect(redirectCalls[0]?.pathname).toBe("/signup/terms/oauth");
  });

  it("/dashboard + onboarding_completed=true → 통과 (E4 게이트 통과)", () => {
    const token = makeToken({ onboarding_completed: true });
    middleware(makeRequest("/dashboard", { dr_session: token }));
    expect(nextCalls).toHaveLength(1);
    expect(redirectCalls).toHaveLength(0);
  });

  // ─── Backward-compat: 구 토큰(claim 없음)은 통과 (H-1 수정) ────────────────
  it("/dashboard + 구 토큰(onboarding_completed claim 없음) → 통과 (backward-compat)", () => {
    const token = makeToken({ sub: "42", email: "user@test.com", tier: "FREE" });
    middleware(makeRequest("/dashboard", { dr_session: token }));
    expect(nextCalls).toHaveLength(1);
    expect(redirectCalls).toHaveLength(0);
  });

  it("디코딩 불가 토큰(malformed) + 보호 경로 → 통과 (null claim은 게이트 안 걸림)", () => {
    middleware(makeRequest("/dashboard", { dr_session: "not.a.real.jwt.at.all" }));
    // decodeJwtPayload 반환 null → claims !== null 조건 미충족 → 통과
    expect(nextCalls).toHaveLength(1);
  });
});
