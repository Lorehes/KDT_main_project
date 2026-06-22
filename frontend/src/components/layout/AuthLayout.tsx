// [목적] 온보딩 스플릿 레이아웃 — 좌측 네이비 피치 패널 + 우측 폼 영역
// [이유] 가입 4단계 모든 화면이 동일한 브랜드 피치 + 폼 2단 구조를 공유
// [사이드 임팩트] (auth) 그룹 내 각 온보딩 page에서 직접 import하여 사용.
//   모바일에서는 좌측 패널 대신 폼 상단에 heading/subtext를 인라인으로 표시하므로,
//   heading prop 변경 시 모바일/데스크톱 양쪽에 영향을 줌.
//   모바일 main을 items-center로 변경 — 로그인처럼 짧은 폼의 하단 빈 공간 해소.
//   폼이 뷰포트보다 길어지면 overflow-y-auto(브라우저 기본 스크롤)가 자동 처리.
// [수정 시 고려사항] 모바일 heading 영역 스타일 변경 시 데스크톱 aside 패널과 톤 일관성 유지.
//   heading에 brand-sky 색상이 포함된 경우 라이트 배경 위 대비 확인 필요.

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
    <div className="grid min-h-screen md:grid-cols-[1fr_2fr]">
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
        <div className="w-full max-w-[460px]">
          {/* 모바일 전용 브랜드 헤딩 — md 이상에서는 좌측 패널이 표시하므로 숨김 */}
          <div className="mb-8 pb-6 border-b border-border md:hidden">
            <div className="mb-4 flex items-center gap-2">
              <BrandMark size={26} />
              <span className="text-xs font-semibold tracking-widest text-muted-foreground">
                DART 실시간 · 30초 이내 해석
              </span>
            </div>
            <h2 className="text-[26px] font-extrabold leading-[1.15] tracking-tight text-foreground">
              {heading}
            </h2>
            {subtext && (
              <p className="mt-2.5 text-sm leading-relaxed text-muted-foreground">{subtext}</p>
            )}
          </div>
          {children}
        </div>
      </main>
    </div>
  );
}
