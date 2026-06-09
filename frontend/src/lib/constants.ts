// [목적] 인증 관련 경로 상수 — 분산된 "/login", "/api/auth/*" 리터럴을 단일 소스로 관리
// [이유] window.location.href = "/login" 이 3곳(client.ts, authStore.ts, auth.ts)에 분산됨.
//   경로 변경 시 파편화 위험 + 오타 가능성 제거. SITE_ORIGIN은 SSR 환경 fetch 절대경로용.
// [사이드 임팩트] client.ts·authStore.ts·auth.ts·broadcast.ts 가 이 파일을 공유.
//   경로 변경 시 이 파일 하나만 수정하면 됨.
// [수정 시 고려사항] NEXT_PUBLIC_SITE_URL 미설정 시 개발 환경에서 localhost:3000 폴백.
//   프로덕션 배포 전 NEXT_PUBLIC_SITE_URL 환경변수 반드시 주입.

export const LOGIN_PATH = "/login" as const;
export const SESSION_PATH = "/api/auth/session" as const;
export const REFRESH_PATH = "/api/auth/refresh" as const;
export const LOGOUT_PATH = "/api/auth/logout" as const;

/**
 * SSR/RSC 컨텍스트에서 절대경로 fetch에 사용하는 사이트 origin.
 * 브라우저: window.location.origin (동적). SSR: NEXT_PUBLIC_SITE_URL 환경변수.
 * client.ts는 클라이언트 전용이지만 안전 장치로 절대경로 사용.
 */
export const SITE_ORIGIN: string =
  (typeof window !== "undefined" ? window.location.origin : null) ??
  process.env.NEXT_PUBLIC_SITE_URL ??
  "http://localhost:3000";
