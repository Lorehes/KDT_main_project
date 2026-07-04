// [목적] 백엔드 REST API fetch 래퍼 — Promise 큐 기반 401 자동 갱신, 에러 파싱, 타입 안전 반환
// [이유] isRefreshing boolean(구현 당시)은 race condition 있음 — 동시 401 시 갱신 중인 요청들이 즉시 실패.
//   Promise 큐(`refreshPromise`)로 교체: 진행 중인 refresh가 있으면 동일 Promise를 await해
//   모든 대기 요청이 refresh 완료 후 일괄 재시도. 무손실 동시 401 처리(fe-auth-token-refresh-flow-rewrite R4).
//   절대경로(SITE_ORIGIN)로 SSR 환경 대비(R5). 5초 timeout으로 Promise 메모리 누수 방지.
// [사이드 임팩트] lib/api/*.ts의 모든 훅이 이 클라이언트를 사용. 인증 방식 변경 시 이 파일만 수정.
//   refreshPromise는 모듈 수준 변수 — 탭 간 공유 안 됨(BroadcastChannel로 별도 처리, broadcast.ts 참고).
// [수정 시 고려사항] 401 인터셉터는 1회만 재시도(재시도 후 401이면 그대로 에러 throw).
//   /auth/ 경로는 인터셉터 제외 — 무한 루프 방지.
//   NEXT_PUBLIC_API_URL 미설정 시 빌드 경고. 프로덕션 배포 전 환경변수 주입 필수.

import { LOGIN_PATH, LOGOUT_PATH, REFRESH_PATH, SITE_ORIGIN } from "@/lib/constants";

if (typeof window !== "undefined" && !process.env.NEXT_PUBLIC_API_URL) {
  console.warn(
    "[apiClient] NEXT_PUBLIC_API_URL이 설정되지 않아 localhost:8080으로 요청합니다. 프로덕션 배포 전 환경변수를 확인하세요.",
  );
}

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

const REFRESH_TIMEOUT_MS = 5_000;

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

// Promise 큐: 동시 401 요청들이 동일 refresh Promise를 await. resolve 후 각자 재시도.
// finally에서 null로 초기화해 다음 refresh 사이클을 허용.
let refreshPromise: Promise<void> | null = null;

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

async function performRefresh(): Promise<void> {
  // 진행 중인 refresh가 있으면 동일 Promise 반환 (race condition 방지)
  if (refreshPromise) return refreshPromise;

  const refreshTask = (async () => {
    const refreshRes = await fetch(`${SITE_ORIGIN}${REFRESH_PATH}`, {
      method: "POST",
      credentials: "include",
    });
    if (!refreshRes.ok) {
      // refresh 실패 → 쿠키 클리어
      await fetch(`${SITE_ORIGIN}${LOGOUT_PATH}`, { method: "POST" }).catch(() => {});
      // 공개 경로(랜딩·로그인·회원가입 등)에서는 리다이렉트 생략 — 비로그인 방문자의 정상 흐름
      if (typeof window !== "undefined") {
        const PUBLIC_PATHS = ["/", "/pricing", "/login", "/signup", "/dashboard/preview"];
        const isPublic = PUBLIC_PATHS.some(
          (p) => window.location.pathname === p || window.location.pathname.startsWith(p + "/")
        );
        if (!isPublic) window.location.href = LOGIN_PATH;
      }
      throw new ApiException({
        status: 401,
        code: "SESSION_EXPIRED",
        message: "세션이 만료되었습니다.",
        path: REFRESH_PATH,
      });
    }
  })();

  const timeoutTask = new Promise<void>((_, reject) =>
    setTimeout(
      () => reject(new Error("[apiClient] Token refresh timeout — 5초 초과")),
      REFRESH_TIMEOUT_MS,
    ),
  );

  refreshPromise = Promise.race([refreshTask, timeoutTask]).finally(() => {
    refreshPromise = null;
  });

  return refreshPromise;
}

export async function apiClient<T>(path: string, init?: RequestInit): Promise<T> {
  let res = await doFetch(path, init);

  // 401 수신 시 refresh 시도 (auth 경로 제외 — 무한 루프 방지, 재시도 1회 제한)
  if (res.status === 401 && !path.includes("/auth/")) {
    await performRefresh();
    res = await doFetch(path, init);
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
