// [목적] 퍼블릭 페이지(랜딩·요금제) 레이아웃 — PublicNavbar + 컨텐츠
// [이유] (auth)·(app) 레이아웃과 분리해 인증 없이 접근 가능한 페이지에 독립 셸 제공.
//   요금제 등 로그인/비로그인 공용 페이지에서 네비바가 인증 상태를 알아야 하므로(로그인 사용자에게
//   '로그인/무료로 시작' CTA 노출 방지), 서버에서 세션 쿠키 presence를 읽어 PublicNavbar에 주입한다.
// [사이드 임팩트] 이 그룹 하위 라우트(/, /pricing 등)는 모두 동일 PublicNavbar(인증 상태 반영)를 공유.
//   cookies() 사용으로 이 레이아웃은 요청별 동적 렌더링이 된다(정적 캐시 불가) — 마케팅 페이지엔 영향 미미.
// [수정 시 고려사항] dr_session은 httpOnly라 클라이언트 JS로 못 읽으므로 presence 판정은 서버(여기)에서만 가능.
//   middleware.ts와 동일한 presence-only 판정(유효성은 API가 검증). 마케팅 페이지 추가 시 이 그룹에 배치.

import { cookies } from "next/headers";
import { PublicNavbar } from "@/components/layout/PublicNavbar";

export default async function PublicLayout({ children }: { children: React.ReactNode }) {
  // dr_session presence만 확인 — middleware.ts:28과 동일 기준(값/만료 검증은 BE 담당)
  const isAuthenticated = Boolean((await cookies()).get("dr_session"));

  return (
    <div className="flex min-h-screen flex-col">
      <PublicNavbar isAuthenticated={isAuthenticated} />
      <main className="flex-1">{children}</main>
    </div>
  );
}
