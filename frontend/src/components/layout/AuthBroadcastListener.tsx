// [목적] 앱 진입 시 인증 상태 초기화 + 다른 탭 로그아웃 이벤트 동기화
// [이유] (1) fetchMe: /dashboard 등 직접 진입·새로고침 시 user null을 복원. LandingRedirect(루트 전용)만으로는
//   인증 그룹 페이지 직접 진입 케이스를 커버하지 못함 → 여기서 1회 호출(R9, frontend-api-integration).
//   (2) BroadcastChannel 구독: useEffect 필요 → Server Component인 (app)/layout.tsx에서 직접 사용 불가.
// [사이드 임팩트] fetchMe는 /users/me 1회 호출. 401 → client.ts 인터셉터가 refresh 시도 후 실패 시 null 세팅.
//   logout/setUser null은 BroadcastChannel 이벤트 수신 시에만 발생.
// [수정 시 고려사항] fetchMe 실패(비로그인 상태 정상)는 authStore 내부에서 catch+null 세팅으로 처리.
//   "refresh" 이벤트 처리 필요 시 subscribeAuthBroadcast 시그니처 확장 후 여기서 분기.

"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { subscribeAuthBroadcast } from "@/lib/auth/broadcast";
import { useAuthStore } from "@/lib/stores/authStore";
import { LOGIN_PATH } from "@/lib/constants";

export function AuthBroadcastListener() {
  const setUser = useAuthStore(s => s.setUser);
  const fetchMe = useAuthStore(s => s.fetchMe);
  const router = useRouter();

  // 직접 진입·새로고침 시 user 복원 — 마운트 1회
  useEffect(() => {
    fetchMe();
  }, []); // fetchMe는 Zustand 안정 참조 — 의존성 제외

  useEffect(() => {
    return subscribeAuthBroadcast(() => {
      setUser(null);
      router.push(LOGIN_PATH);
    });
  }, [setUser, router]);

  return null;
}
