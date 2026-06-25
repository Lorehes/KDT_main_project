// [목적] 앱 전역 경로·이메일·기타 문자열 상수 — 리터럴 파편화 방지
// [이유] LOGIN_PATH 3곳, SUPPORT_EMAIL 2곳에 하드코딩된 리터럴이 산재해 변경 시 파편화 위험.
//   단일 소스로 집중 관리. SITE_ORIGIN은 SSR 환경 fetch 절대경로용.
// [사이드 임팩트] client.ts·authStore.ts·auth.ts·broadcast.ts가 LOGIN_PATH 공유.
//   disclosures/[id]/page.tsx·settings/page.tsx가 SUPPORT_EMAIL 사용.
// [수정 시 고려사항] NEXT_PUBLIC_SITE_URL 미설정 시 개발 환경에서 localhost:3000 폴백.
//   프로덕션 배포 전 NEXT_PUBLIC_SITE_URL 환경변수 반드시 주입.

// [목적] 플랜 티어 레이블·가격 상수 — TopBar·Settings·TierGate 간 리터럴 파편화 방지
// [이유] TIER_LABEL이 TopBar·settings/page.tsx·TierGate에 각각 로컬 선언되어 있어
//   값 불일치(PRO vs Pro) 발생. 단일 소스로 통합.
// [사이드 임팩트] TopBar의 TIER_LABEL 로컬 선언(FREE:"Free 플랜" 포함)과 settings의 선언이 병합됨.
//   TierGate는 PRO/PREMIUM만 사용 — TIER_LABEL에서 해당 키를 읽으면 됨.
// [수정 시 고려사항] 요금 변경 시 이 파일만 수정하면 모든 사용처에 반영됨.
export const TIER_LABEL: Record<string, string> = {
  FREE: "Free",
  PRO: "Pro",
  PREMIUM: "Premium",
};

export const TIER_LABEL_LONG: Record<string, string> = {
  FREE: "Free 플랜",
  PRO: "Pro 플랜 · 무제한 종목",
  PREMIUM: "Premium 플랜 · 모든 기능",
};

export const TIER_PRICE: Record<string, string> = {
  FREE: "무료",
  PRO: "₩9,900/월",
  PREMIUM: "₩29,900/월",
};

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
