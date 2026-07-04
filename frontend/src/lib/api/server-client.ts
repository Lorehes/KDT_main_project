// [목적] Server Components / Server Actions 전용 BE API fetch 클라이언트.
//   httpOnly 쿠키(dr_session)에서 access token을 읽어 Authorization 헤더로 BE에 전달.
// [이유] client.ts는 브라우저 context에서 동작(credentials:"include"로 쿠키 자동 전송).
//   Server Component에서는 브라우저 쿠키 자동 전송이 없으므로 next/headers cookies() API로 직접 읽어야 함.
//   401 처리는 호출자 책임 — Server Component에서 redirect()를 직접 호출.
// [사이드 임팩트] 이 파일을 클라이언트 번들에 포함시키면 next/headers import 오류 발생.
//   Server Component·Route Handler·Server Action에서만 import할 것.
//   cache:"no-store"로 요청마다 최신 데이터 보장(동적 렌더링).
// [수정 시 고려사항] 토큰 만료 시 401 throw 후 상위 레이아웃(layout.tsx)에서 redirect("/login") 처리 권장.
//   인증 불필요한 공개 API(공시 목록 등)는 이 클라이언트 대신 일반 fetch 사용.

import { cookies } from "next/headers";

const BASE_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080/api/v1";

export class ServerApiException extends Error {
  constructor(
    public readonly status: number,
    public readonly path: string,
  ) {
    super(`Server API error: ${status} ${path}`);
    this.name = "ServerApiException";
  }
}

/**
 * Server Component·Server Action 전용 BE API 클라이언트.
 * dr_session 쿠키가 없으면 인증 없이 요청 (공개 엔드포인트용).
 * 401 응답 시 ServerApiException throw — 호출자에서 redirect("/login") 처리.
 */
export async function serverApiClient<T>(path: string, init?: RequestInit): Promise<T> {
  const cookieStore = await cookies();
  const token = cookieStore.get("dr_session")?.value;

  const res = await fetch(`${BASE_URL}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...init?.headers,
    },
    cache: "no-store",
  });

  if (res.status === 401) {
    throw new ServerApiException(401, path);
  }

  if (!res.ok) {
    throw new ServerApiException(res.status, path);
  }

  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}
