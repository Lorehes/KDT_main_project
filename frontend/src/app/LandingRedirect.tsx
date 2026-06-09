"use client";

// [목적] 랜딩 페이지 인증 리다이렉트 — 로그인 세션 있으면 /dashboard로 이동
// [이유] page.tsx는 서버 컴포넌트로 유지(metadata export). useEffect는 클라이언트 전용이라 분리
// [사이드 임팩트] authStore.fetchMe가 최초 마운트 시 /users/me를 호출. 비로그인 시 404→무시
// [수정 시 고려사항] 쿠키 기반 SSR 리다이렉트로 전환 가능(middleware.ts에서 처리). 현재는 클라이언트 플리커 허용

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuthStore } from "@/lib/stores/authStore";

export function LandingRedirect() {
  const router = useRouter();
  const { user, fetchMe } = useAuthStore();

  useEffect(() => {
    fetchMe().then(() => {
      if (useAuthStore.getState().user) {
        router.replace("/dashboard");
      }
    });
  // fetchMe와 router는 안정적 참조
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return null;
}
