// [목적] 동의 상태 API 클라이언트 + TanStack Query 훅 — 재동의 흐름 및 상태 조회
// [이유] GET /consents/status로 requires_renewal 확인 후 FE가 /signup/terms 강제 이동.
//   POST /consents는 약관 버전 변경 시 재동의 기록(초기 가입 동의는 POST /auth/signup에 포함).
// [사이드 임팩트] useConsentStatus는 로그인 후 AuthBroadcastListener 또는 대시보드 진입 시 호출 권장.
// [수정 시 고려사항] requires_renewal=true 응답 시 FE가 /signup/terms로 redirect — 이 훅이 판단 제공만 함.

import { useMutation, useQuery } from "@tanstack/react-query";
import { apiClient } from "./client";

export interface ConsentItem {
  consent_type: string;
  agreed: boolean;
  policy_version: string | null;
  is_current_version: boolean;
  agreed_at: string | null;
}

export interface ConsentStatusResponse {
  requires_renewal: boolean;
  consents: ConsentItem[];
}

export interface ConsentBody {
  terms_version: string;
  privacy_version: string;
  marketing_opt_in?: boolean;
}

/** 최신 동의 상태 조회 — requires_renewal=true 시 재동의 유도 */
export function useConsentStatus() {
  return useQuery({
    queryKey: ["consent-status"],
    queryFn: () => apiClient<ConsentStatusResponse>("/consents/status"),
    staleTime: 5 * 60_000,
  });
}

/** 재동의 기록 — 약관 버전 변경 후 기존 사용자 재동의 */
export function usePostConsent() {
  return useMutation({
    mutationFn: (body: ConsentBody) =>
      apiClient<void>("/consents", { method: "POST", body: JSON.stringify(body) }),
  });
}
