// [목적] 대시보드 상단 통계 카드 — 단순 수치 카드(StatCard) + 호재/중립/악재/보류 4분할 카드(SentimentStatCard).
//        실 대시보드(page.tsx)와 비로그인 미리보기(preview/page.tsx)가 공유.
// [이유] 두 페이지가 동일한 카드 마크업을 중복 보유하던 것을 단일 출처로 통합(DRY).
//        4상태(호재/중립/악재/보류)를 1개 카드에 색상+구분선+라벨로 묶어 한눈에 비교 가능하게 함.
// [사이드 임팩트] sentiment 색은 디자인 토큰(--color-sentiment-*)만 사용 (design_structure §2.2) —
//        호재=red / 중립=gray / 악재=blue / 보류=보라(#5B43C0). 중립(NEUTRAL)≠보류(is_withheld) — 도메인상 별개.
//        색 단독 사용 금지(WCAG 6-5) 위해 색 + 텍스트 라벨 병기.
// [수정 시 고려사항] 토큰 hue 변경 시 globals.css의 --sentiment-* 만 수정하면 본 카드도 자동 반영.
//        손익 등 추가 지표 카드는 별도 StatCard variant로 확장(가짜 금융수치 하드코딩 금지).

type StatCardProps = {
  label: string;
  value: number | string;
  unit?: string;
  /** 보조 안내(예: "DB 연동 필요") — 회색 캡션으로 표기. 데이터 미연동 placeholder 카드용 */
  note?: string;
  /** note가 있을 때 값을 흐리게 — 아직 실데이터가 아님을 시각적으로 표현 */
  muted?: boolean;
};

export function StatCard({ label, value, unit, note, muted }: StatCardProps) {
  return (
    <li className="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <p className="text-xs font-semibold text-muted-foreground">{label}</p>
      <p
        className={`mt-2.5 text-3xl font-extrabold leading-none ${muted ? "text-muted-foreground/60" : "text-foreground"}`}
      >
        {value}
        {unit && <small className="ml-1 text-sm font-semibold text-muted-foreground">{unit}</small>}
      </p>
      {note && <p className="mt-1.5 text-[11px] font-medium text-muted-foreground">{note}</p>}
    </li>
  );
}

type SentimentStatCardProps = {
  positive: number;
  neutral: number;
  negative: number;
  withheld: number;
};

// 호재/중립/악재/보류 4분할 — 색상 + 구분선 + 라벨로 명확히 구분.
// 중립(NEUTRAL, 회색)과 보류(is_withheld, 보라)는 도메인상 다른 상태 — 별도 칸으로 표기(CLAUDE.md §1·§6-6).
export function SentimentStatCard({ positive, neutral, negative, withheld }: SentimentStatCardProps) {
  const segments = [
    { key: "positive", label: "호재", value: positive, tone: "text-[color:var(--color-sentiment-positive)]" },
    { key: "neutral", label: "중립", value: neutral, tone: "text-[color:var(--color-sentiment-neutral)]" },
    { key: "negative", label: "악재", value: negative, tone: "text-[color:var(--color-sentiment-negative)]" },
    { key: "withheld", label: "보류", value: withheld, tone: "text-[color:var(--color-sentiment-withheld)]" },
  ];

  return (
    <li className="rounded-2xl border border-border bg-card p-5 shadow-sm">
      <p className="text-xs font-semibold text-muted-foreground">호재 · 중립 · 악재 · 보류</p>
      <div className="mt-2.5 grid grid-cols-4 divide-x divide-border">
        {segments.map(({ key, label, value, tone }) => (
          <div key={key} className="flex flex-col items-center gap-1 px-0.5">
            <span className={`text-xl font-extrabold leading-none ${tone}`}>{value}</span>
            <span className={`text-[10px] font-bold tracking-tight ${tone}`}>{label}</span>
          </div>
        ))}
      </div>
    </li>
  );
}
