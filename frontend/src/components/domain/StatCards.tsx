// [목적] 대시보드 상단 통계 카드 — 단순 수치 카드(StatCard) + 호재/중립/악재/보류 4분할 카드(SentimentStatCard) +
//        평가 손익 카드(PnlStatCard).
//        실 대시보드(page.tsx)와 비로그인 미리보기(preview/page.tsx)가 공유.
// [이유] 두 페이지가 동일한 카드 마크업을 중복 보유하던 것을 단일 출처로 통합(DRY).
//        4상태(호재/중립/악재/보류)를 1개 카드에 색상+구분선+라벨로 묶어 한눈에 비교 가능하게 함.
//        PnlStatCard: 한국 시장 컨벤션(상승=빨강 ▲ / 하락=파랑 ▼) — WCAG 색 단독 금지(§6-5) → 색+아이콘+텍스트 병기.
// [사이드 임팩트] sentiment 색은 디자인 토큰(--color-sentiment-*)만 사용 (design_structure §2.2) —
//        호재=red / 중립=gray / 악재=blue / 보류=보라(#5B43C0). 중립(NEUTRAL)≠보류(is_withheld) — 도메인상 별개.
//        색 단독 사용 금지(WCAG 6-5) 위해 색 + 텍스트 라벨 병기.
// [수정 시 고려사항] 토큰 hue 변경 시 globals.css의 --sentiment-* 만 수정하면 본 카드도 자동 반영.
//        PnlStatCard는 투자 권유 표현 금지(CLAUDE.md §7) — 수치/방향 표시만, "매수/매도 추천" 문구 절대 금지.

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
    <li className="flex flex-col rounded-2xl border border-border bg-card px-5 py-2.5 shadow-sm">
      <p className="text-xs font-semibold text-muted-foreground">{label}</p>
      <div className="flex flex-1 items-center justify-center pt-2">
        <p className={`text-4xl font-extrabold leading-none ${muted ? "text-muted-foreground/60" : "text-foreground"}`}>
          {value}
          {unit && <small className="ml-1.5 text-base font-semibold text-muted-foreground">{unit}</small>}
        </p>
        {note && <p className="mt-1 text-xs font-medium text-muted-foreground">{note}</p>}
      </div>
    </li>
  );
}

type SentimentStatCardProps = {
  positive: number;
  neutral: number;
  negative: number;
  withheld: number;
};

type PnlStatCardProps = {
  /** 평가 손익 (원). null = 데이터 없음(종가 미수집·포트폴리오 없음). */
  pnl: number | null;
  /** 수익률(%). null = 매수금액 0 또는 데이터 없음. */
  pnlRate: number | null;
  /** 종가 기준일 (YYYY-MM-DD). null이면 미수집 메시지 표시. */
  asOf: string | null;
  /** 미수집 종목 수 — "n개 종가 미수집" 보조 문구용. */
  unpricedCount: number;
};

/**
 * 한국 금융 앱 표준 컴팩트 금액 표기: 만 단위(10,000원 이상)·억 단위(1억 이상).
 * 좁은 2열 카드에서 "1,234,000원"처럼 긴 숫자가 줄바꿈되는 문제 방지.
 * 부호는 버림(절대값) — 방향 표시는 호출부가 아이콘/부호로 병기(WCAG 색 단독 금지).
 */
export const formatKrwCompact = (amount: number): { value: string; unit: string } => {
  const abs = Math.abs(amount);
  if (abs >= 100_000_000) {
    const eok = abs / 100_000_000;
    return { value: eok % 1 === 0 ? eok.toFixed(0) : eok.toFixed(1), unit: "억원" };
  }
  if (abs >= 10_000) {
    const man = abs / 10_000;
    return { value: man % 1 === 0 ? man.toFixed(0) : man.toFixed(1), unit: "만원" };
  }
  return { value: new Intl.NumberFormat("ko-KR").format(Math.round(abs)), unit: "원" };
};

/**
 * 평가 손익 카드 — 한국 시장 컨벤션(상승=빨강 ▲, 하락=파랑 ▼).
 * 색상 단독 표현 금지 → 색 + 방향 아이콘 + 부호 텍스트 병기(WCAG 2.1 AA, CLAUDE.md §6-5).
 * 투자 권유 표현 없음 — 수치와 방향만 표시(자본시장법, CLAUDE.md §7).
 */
export function PnlStatCard({ pnl, pnlRate, asOf, unpricedCount }: PnlStatCardProps) {
  const hasData = pnl !== null;
  const isProfit = hasData && pnl > 0;
  const isLoss   = hasData && pnl < 0;

  const pnlColorClass = isProfit
    ? "text-[color:var(--color-sentiment-positive)]"
    : isLoss
      ? "text-[color:var(--color-sentiment-negative)]"
      : "text-foreground";

  const icon = isProfit ? "▲" : isLoss ? "▼" : null;

  const formattedPnl = hasData ? formatKrwCompact(pnl) : null;

  const formattedRate = pnlRate !== null
    ? `${pnlRate > 0 ? "+" : ""}${pnlRate.toFixed(2)}%`
    : null;

  return (
    <li className="flex flex-col rounded-2xl border border-border bg-card px-5 py-2.5 shadow-sm">
      <p className="text-xs font-semibold text-muted-foreground">평가 손익</p>
      <div className="flex flex-1 items-center justify-center pt-2">
        {hasData ? (
          <div className="flex flex-col items-center gap-1">
            <p
              className={`flex items-baseline gap-2 text-4xl font-extrabold leading-none ${pnlColorClass}`}
              aria-label={`평가 손익 ${isProfit ? "수익" : isLoss ? "손실" : "없음"}`}
            >
              <span>
                {icon && <span aria-hidden className="mr-0.5 text-2xl">{icon}</span>}
                {formattedPnl!.value}
                <small className="ml-1 text-sm font-semibold text-muted-foreground">{formattedPnl!.unit}</small>
              </span>
              {formattedRate && (
                <span className={`text-sm font-bold ${pnlColorClass}`} aria-label={`수익률 ${formattedRate}`}>
                  {formattedRate}
                </span>
              )}
            </p>
            {asOf && (
              <p className="text-xs font-medium text-muted-foreground">{asOf} 종가 기준</p>
            )}
            {unpricedCount > 0 && (
              <p className="text-xs text-muted-foreground">{unpricedCount}개 종목 종가 미수집</p>
            )}
          </div>
        ) : (
          <p className="text-3xl font-extrabold leading-none text-muted-foreground/60" aria-label="평가 손익 미수집">
            —
            <span className="ml-2 text-xs font-semibold text-muted-foreground/60">종가 수집 중</span>
          </p>
        )}
      </div>
    </li>
  );
}

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
    <li className="flex flex-col rounded-2xl border border-border bg-card px-5 py-2.5 shadow-sm">
      <p className="text-xs font-semibold text-muted-foreground">호재 · 중립 · 악재 · 보류</p>
      <div className="flex flex-1 items-center justify-center pt-2">
        <div className="grid grid-cols-4 divide-x divide-border">
          {segments.map(({ key, label, value, tone }) => (
            <div key={key} className="flex flex-col items-center gap-1 px-3">
              <span className={`text-2xl font-extrabold leading-none ${tone}`}>{value}</span>
              <span className={`text-[11px] font-bold tracking-tight ${tone}`}>{label}</span>
            </div>
          ))}
        </div>
      </div>
    </li>
  );
}
