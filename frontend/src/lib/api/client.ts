// [목적] 백엔드 REST API fetch 래퍼 — Bearer JWT(httpOnly cookie), 에러 파싱, 타입 안전 반환
// [이유] 모든 API 호출을 단일 클라이언트로 중앙화해 인증 헤더·에러 처리를 일관되게 유지
// [사이드 임팩트] lib/api/*.ts의 모든 훅이 이 클라이언트를 사용. 인증 방식 변경 시 이 파일만 수정
// [수정 시 고려사항] 401 응답 시 /auth/refresh 시도 후 재요청하는 인터셉터 추가 필요(현재 미구현).
//   백엔드 base URL은 환경변수 NEXT_PUBLIC_API_URL로 주입

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

export async function apiClient<T>(
  path: string,
  init?: RequestInit,
): Promise<T> {
  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    credentials: "include", // httpOnly cookie(dr_session) 자동 포함
    headers: {
      "Content-Type": "application/json",
      ...init?.headers,
    },
  });

  if (!res.ok) {
    let body: ApiError;
    try {
      body = await res.json();
    } catch {
      body = {
        status: res.status,
        code: "UNKNOWN_ERROR",
        message: `HTTP ${res.status}`,
        path,
      };
    }
    throw new ApiException(body);
  }

  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}
