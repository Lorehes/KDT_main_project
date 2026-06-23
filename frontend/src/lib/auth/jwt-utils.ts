// [목적] JWT payload 디코딩 유틸 — 서명 검증 없이 라우팅 게이트 전용으로 사용.
// [이유] middleware.ts는 Edge Runtime에서 실행되어 Next.js 전용 API에 묶여 있어 단독 테스트 어려움.
//   순수 함수(decodeJwtPayload)를 분리해 Vitest로 단위 테스트 가능하게 함.
// [사이드 임팩트] 이 모듈은 browser/Edge Runtime/Node.js 모두에서 동작해야 함.
//   atob는 세 환경 모두에서 사용 가능 (Node.js 16+ 포함).
// [수정 시 고려사항] 이 함수는 라우팅 전용 — 인가(authorization) 목적으로 사용 금지.
//   서명 검증이 필요한 경우는 BE JwtAuthenticationFilter가 담당.

/**
 * JWT payload를 서명 검증 없이 디코딩.
 * 파싱 실패·잘못된 형식 시 null 반환 (throw 없음).
 * 라우팅 게이트 전용 — 인가 목적 사용 금지.
 */
export function decodeJwtPayload(token: string): Record<string, unknown> | null {
  try {
    const part = token.split(".")[1];
    if (!part) return null;
    // base64url → base64 변환 (RFC 4648)
    const base64 = part.replace(/-/g, "+").replace(/_/g, "/");
    return JSON.parse(atob(base64));
  } catch {
    return null;
  }
}
