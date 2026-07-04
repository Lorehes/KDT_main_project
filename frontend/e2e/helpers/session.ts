// [목적] Playwright e2e 공통 세션 픽스처 — fake JWT 생성 + dr_session 쿠키 + BE /users/me 모킹
// [이유] makeFakeSessionJwt/setAuthCookie가 keyboard-nav 등 여러 spec에 중복 정의됨. 단일 출처로 추출.
// [사이드 임팩트] 이 helper를 사용하는 모든 spec은 dr_session 쿠키 기반 인증을 전제함.
//   미들웨어(middleware.ts)는 JWT 서명 검증 없이 payload만 디코딩(라우팅 목적) — fake signature 사용 가능.
// [수정 시 고려사항] JWT payload의 role/onboarding_completed 필드가 미들웨어 라우팅에 영향.
//   PRO/PREMIUM 기능 테스트 시 tier 파라미터 조정. exp: 9_999_999_999은 충분히 미래값이라 갱신 불필요.

import type { BrowserContext } from "@playwright/test";

export const BE_BASE = "http://localhost:8080/api/v1";

/**
 * JWT payload를 base64url 인코딩해 dr_session 쿠키용 토큰 생성.
 * 미들웨어는 서명 없이 payload만 디코딩 — fake signature로 충분.
 */
export function makeFakeSessionJwt(opts?: { tier?: string }): string {
  const header = Buffer.from(JSON.stringify({ alg: "HS256", typ: "JWT" })).toString("base64url");
  const payload = Buffer.from(
    JSON.stringify({
      sub: "1",
      email: "test@example.com",
      role: opts?.tier ?? "FREE",
      onboarding_completed: true,
      exp: 9_999_999_999,
    }),
  ).toString("base64url");
  return `${header}.${payload}.fake_sig`;
}

/** dr_session 쿠키를 BrowserContext에 설정한다. */
export async function setAuthCookie(context: BrowserContext, tier = "FREE") {
  await context.addCookies([
    {
      name: "dr_session",
      value: makeFakeSessionJwt({ tier }),
      domain: "localhost",
      path: "/",
    },
  ]);
}

/**
 * BE GET /api/v1/users/me 응답 픽스처.
 * 민감정보(매수가·보유 종목) 없음 — 공개 식별자만 포함.
 */
export function mockMeResponse(nickname = "테스트사용자") {
  return {
    id: 1,
    email: "test@example.com",
    nickname,
    tier: "FREE",
    tier_expires_at: null,
  };
}
