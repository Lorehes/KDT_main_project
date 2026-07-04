"use client";

// [목적] 과거 유사 공시 실측 D+1~D+5 평균 등락 막대 차트(disclosure-detail-redesign #9, krx-price-timeseries Wave C)
// [이유] 방식 A(실측 평균) — LLM 미래 예측 아님. 자본시장법 §11.1: "예측 단정" 금지 → "과거 유사 사례 평균" 프레이밍.
//   색+부호+aria로 색맹 배려(CLAUDE.md §6-5). 호재(상승)=빨강/악재(하락)=파랑 토큰(한국 증시 관행).
// [사이드 임팩트] DisclosureAnalysis.price_reaction_forecast를 소비. Pro 전용. 표본 없으면 BE가 필드 생략 → 미노출.
// [수정 시 고려사항] sample_size 낮으면 신뢰도 낮음 — 문구로 표본 수 병기. 수정주가 미보정(raw) 한계는 BE 주석 참조.

import {
  ResponsiveContainer, BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, Cell, ReferenceLine,
} from "recharts";
import type { PriceReactionForecast } from "@/lib/api/disclosures";

export function PriceForecastChart({ forecast }: { forecast: PriceReactionForecast }) {
  const chartData = forecast.series.map((s) => ({ label: `D+${s.day}`, pct: s.avg_pct }));

  const summary = forecast.series
    .map((s) => `D+${s.day} ${s.avg_pct > 0 ? "+" : ""}${s.avg_pct.toFixed(1)}%`)
    .join(", ");
  const avg = forecast.avg_5d_pct;

  return (
    <div>
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <p className="text-sm text-muted-foreground">
          과거 유사 사례 <span className="font-bold text-foreground">{forecast.sample_size}건</span> 평균 등락
        </p>
        <p className={`text-sm font-extrabold ${avg >= 0 ? "text-[color:var(--color-sentiment-positive)]" : "text-[color:var(--color-sentiment-negative)]"}`}>
          최종일 평균 {avg >= 0 ? "+" : ""}{avg.toFixed(1)}%
        </p>
      </div>

      <div role="img" aria-label={`과거 유사 공시 ${forecast.sample_size}건의 평균 등락: ${summary}`}>
        <ResponsiveContainer width="100%" height={160}>
          <BarChart data={chartData} margin={{ top: 8, right: 4, left: -16, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" vertical={false} />
            <XAxis dataKey="label" tick={{ fontSize: 11, fill: "var(--color-muted-foreground)" }} tickLine={false} axisLine={false} />
            <YAxis tickFormatter={(v) => `${v}%`} tick={{ fontSize: 11, fill: "var(--color-muted-foreground)" }} tickLine={false} axisLine={false} />
            <Tooltip
              formatter={(v: number) => [`${v > 0 ? "+" : ""}${v.toFixed(1)}%`, "평균 등락"]}
              contentStyle={{ borderRadius: 8, border: "1px solid var(--color-border)", fontSize: 12 }}
            />
            <ReferenceLine y={0} stroke="var(--color-border)" />
            <Bar dataKey="pct" radius={[4, 4, 0, 0]} maxBarSize={40}>
              {chartData.map((entry, i) => (
                <Cell key={i} fill={entry.pct >= 0 ? "var(--color-sentiment-positive)" : "var(--color-sentiment-negative)"} />
              ))}
            </Bar>
          </BarChart>
        </ResponsiveContainer>
      </div>

      <p className="mt-2 text-xs text-muted-foreground">
        미래 예측이 아니라 <strong className="font-semibold text-foreground">과거 유사 공시의 실제 5일 등락 평균</strong>입니다. 투자 판단의 근거가 아닙니다.
      </p>
    </div>
  );
}
