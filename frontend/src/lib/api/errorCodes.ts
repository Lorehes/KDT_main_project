// [목적] BE GlobalExceptionHandler가 반환하는 에러 코드 상수 — 문자열 리터럴 중복 제거
// [이유] portfolios/new/page.tsx 등 여러 곳에서 "BUSINESS_RULE_VIOLATION" 등을 직접 비교함(R11).
//   오타·변경 파편화 방지. BE ErrorCode enum name과 1:1 대응.
// [사이드 임팩트] BE GlobalExceptionHandler의 에러 코드 변경 시 이 파일도 갱신 필요.
// [수정 시 고려사항] 새 에러 코드 추가 시 여기서만 정의하고 사용처는 이 상수를 import.

export const API_ERROR_CODES = {
  BUSINESS_RULE_VIOLATION: "BUSINESS_RULE_VIOLATION",
  DUPLICATE_RESOURCE:      "DUPLICATE_RESOURCE",
  VALIDATION_ERROR:        "VALIDATION_ERROR",
  RESOURCE_NOT_FOUND:      "RESOURCE_NOT_FOUND",
  UNAUTHORIZED:            "UNAUTHORIZED",
  FORBIDDEN:               "FORBIDDEN",
} as const;

export type ApiErrorCode = typeof API_ERROR_CODES[keyof typeof API_ERROR_CODES];
