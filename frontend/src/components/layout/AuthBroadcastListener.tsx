// [목적] 다른 탭의 로그아웃 이벤트를 수신해 현재 탭 인증 상태 동기화
// [이유] BroadcastChannel 구독은 useEffect가 필요 → Server Component인 (app)/layout.tsx에서 직접 사용 불가.
//   Client Component로 분리해 layout에 마운트.
// [사이드 임팩트] subscribeAuthBroadcast cleanup은 useEffect 반환값으로 자동 처리. 메모리 누수 없음.
// [수정 시 고려사항] "refresh" 이벤트 처리가 필요해지면 subscribeAuthBroadcast 시그니처 확장 후 여기서 분기.

"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { subscribeAuthBroadcast } from "@/lib/auth/broadcast";
import { useAuthStore } from "@/lib/stores/authStore";
import { LOGIN_PATH } from "@/lib/constants";

export function AuthBroadcastListener() {
  const setUser = useAuthStore((s) => s.setUser);
  const router = useRouter();

  useEffect(() => {
    return subscribeAuthBroadcast(() => {
      setUser(null);
      router.push(LOGIN_PATH);
    });
  }, [setUser, router]);

  return null;
}
