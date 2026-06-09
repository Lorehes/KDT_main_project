// [목적] 백엔드 REST API fetch 래퍼 — httpOnly 쿠키 인증, 401 자동 갱신, 에러 파싱, 타입 안전 반환
// [이유] 모든 API 호출을 단일 클라이언트로 중앙화해 인증 헤더·에러 처리를 일관되게 유지
// [사이드 임팩트] lib/api/*.ts의 모든 훅이 이 클라이언트를 사용. 인증 방식 변경 시 이 파일만 수정
// [수정 시 고려사항] 401 인터셉터는 1회만 재시도. /api/auth/refresh 자체가 401이면 로그아웃 처리.
//   NEXT_PUBLIC_API_URL 미설정 시 빌드 시점에 경고. 프로덕션에서는 반드시 환경변수 주입 필요

if (typeof window !== "undefined" && !process.env.NEXT_PUBLIC_API_URL) {
  console.warn("[apiClient] NEXT_PUBLIC_API_URL이 설정되지 않아 localhost:8080으로 요청합니다. 프로덕션 배포 전 환경변수를 확인하세요.");
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export interface ApiError {
  status: number;
  code: string;
  message: string;
  path: string;
  errors?: { field: string; reason: string }[];
}

export class ApiException extends Error {
  constructor(public readonly body: ApiError) {
    super(body.message);
    this.name = "ApiException";
  }
}

let isRefreshing = false;

async function doFetch(path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${BASE_URL}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });
}

export async function apiClient<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  let res = await doFetch(path, init);

  // 401 수신 시 1회 갱신 시도 (무한 루프 방지: isRefreshing 플래그)
  if (res.status === 401 && !isRefreshing && !path.includes("/auth/")) {
    isRefreshing = true;
    try {
      const refreshRes = await fetch("/api/auth/refresh", { method: "POST" });
      if (refreshRes.ok) {
        // 쿠키 갱신 성공 → 원 요청 재시도
        res = await doFetch(path, init);
      } else {
        // refresh 실패 → 로그아웃 (쿠키 클리어)
        await fetch("/api/auth/logout", { method: "POST" });
        if (typeof window !== "undefined") window.location.href = "/login";
        throw new ApiException({ status: 401, code: "UNAUTHENTICATED", message: "세션이 만료되었습니다", path });
      }
    } finally {
      isRefreshing = false;
    }
  }

  if (!res.ok) {
    let body: ApiError;
    try {
      body = await res.json();
    } catch {
      body = { status: res.status, code: "UNKNOWN_ERROR", message: `HTTP ${res.status}`, path };
    }
    throw new ApiException(body);
  }

  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}
