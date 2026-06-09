// [목적] 온보딩 스플릿 레이아웃 — 좌측 네이비 피치 패널 + 우측 폼 영역
// [이유] 가입 4단계 모든 화면이 동일한 브랜드 피치 + 폼 2단 구조를 공유
// [사이드 임팩트] (auth) 그룹 내 각 온보딩 page에서 직접 import하여 사용
// [수정 시 고려사항] 모바일(< md)에서는 좌측 패널 숨김, 폼만 풀화면. 좌측 카피는 page마다 다름

import { BrandMark } from "./BrandMark";

interface AuthLayoutProps {
  /** 좌측 패널 메인 헤드라인 (JSX 허용) */
  heading: React.ReactNode;
  /** 좌측 패널 서브텍스트 */
  subtext?: string;
  children: React.ReactNode;
}

export function AuthLayout({ heading, subtext, children }: AuthLayoutProps) {
  return (
    <div className="grid min-h-screen md:grid-cols-2">
      {/* 좌측 피치 패널 — 웹만 */}
      <aside className="hidden flex-col justify-between bg-[color:var(--color-brand-navy)] p-16 text-white md:flex">
        <div className="flex items-center gap-2.5">
          <BrandMark size={32} />
          <span className="text-sm font-semibold tracking-widest text-blue-300">DART 실시간 · 30초 이내 해석</span>
        </div>

        <div>
          <h2 className="text-[40px] font-extrabold leading-[1.12] tracking-tight">
            {heading}
          </h2>
          {subtext && (
            <p className="mt-4 max-w-[42ch] text-base leading-relaxed text-blue-200">{subtext}</p>
          )}
        </div>

        <p className="border-t border-white/10 pt-5 text-sm text-blue-300/80">
          "공시는 떴는데 의미를 모르겠다" — 더 이상 검색하지 마세요. 공시레이더가 호재·악재를 판별해 드립니다.
        </p>
      </aside>

      {/* 우측 폼 영역 */}
      <main className="flex items-center justify-center bg-background p-8 md:p-16">
        <div className="w-full max-w-[460px]">{children}</div>
      </main>
    </div>
  );
}
