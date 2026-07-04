// [목적] 공시레이더 브랜드 아이콘 — 레이더 원형 SVG 마크
// [이유] NavBar·AppBar·SideBar에서 공통으로 사용하는 독립 아이콘 컴포넌트
// [사이드 임팩트] 없음 (presentational only)
// [수정 시 고려사항] 다크모드 시 stroke 색상을 sky 토큰으로 유지

interface BrandMarkProps {
  size?: number;
  className?: string;
}

export function BrandMark({ size = 36, className }: BrandMarkProps) {
  return (
    <div
      className={`grid place-items-center rounded-[9px] bg-[color:var(--color-brand-navy)] ${className ?? ""}`}
      style={{ width: size, height: size }}
      aria-hidden="true"
    >
      <svg
        width={size * 0.56}
        height={size * 0.56}
        viewBox="0 0 24 24"
        fill="none"
        stroke="var(--color-brand-sky)"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="12" cy="12" r="8.5" />
        <circle cx="12" cy="12" r="3.8" />
        <path d="M12 12 18.5 7.5" />
      </svg>
    </div>
  );
}
