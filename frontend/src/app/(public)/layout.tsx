// [목적] 퍼블릭 페이지(랜딩·요금제) 레이아웃 — PublicNavbar + 컨텐츠
// [이유] (auth)·(app) 레이아웃과 분리해 인증 없이 접근 가능한 페이지에 독립 셸 제공
// [사이드 임팩트] 이 그룹 하위 라우트는 모두 PublicNavbar를 공유
// [수정 시 고려사항] 마케팅 페이지 추가 시 이 레이아웃 그룹에 배치

import { PublicNavbar } from "@/components/layout/PublicNavbar";

export default function PublicLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="flex min-h-screen flex-col">
      <PublicNavbar />
      <main className="flex-1">{children}</main>
    </div>
  );
}
