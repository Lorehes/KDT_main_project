// [목적] 앱 전역 경로·이메일·기타 문자열 상수 — 리터럴 파편화 방지
// [이유] LOGIN_PATH 3곳, SUPPORT_EMAIL 2곳에 하드코딩된 리터럴이 산재해 변경 시 파편화 위험.
//   단일 소스로 집중 관리. SITE_ORIGIN은 SSR 환경 fetch 절대경로용.
// [사이드 임팩트] client.ts·authStore.ts·auth.ts·broadcast.ts가 LOGIN_PATH 공유.
//   disclosures/[id]/page.tsx·settings/page.tsx가 SUPPORT_EMAIL 사용.
// [수정 시 고려사항] NEXT_PUBLIC_SITE_URL 미설정 시 개발 환경에서 localhost:3000 폴백.
//   프로덕션 배포 전 NEXT_PUBLIC_SITE_URL 환경변수 반드시 주입.

export const LOGIN_PATH = "/login" as const;
export const SESSION_PATH = "/api/auth/session" as const;
export const REFRESH_PATH = "/api/auth/refresh" as const;
export const LOGOUT_PATH = "/api/auth/logout" as const;

/** 고객 지원 이메일 — 면책 고지·탈퇴 안내 등에 사용. */
export const SUPPORT_EMAIL = "support@dartcommons.kr" as const;

/**
 * SSR/RSC 컨텍스트에서 절대경로 fetch에 사용하는 사이트 origin.
 * 브라우저: window.location.origin (동적). SSR: NEXT_PUBLIC_SITE_URL 환경변수.
 * client.ts는 클라이언트 전용이지만 안전 장치로 절대경로 사용.
 */
export const SITE_ORIGIN: string =
  (typeof window !== "undefined" ? window.location.origin : null) ??
  process.env.NEXT_PUBLIC_SITE_URL ??
  "http://localhost:3000";
