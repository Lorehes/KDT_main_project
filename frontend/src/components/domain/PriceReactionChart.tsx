"use client";

// [목적] 과거 유사 공시 5일 주가 반응 Recharts 차트 (Pro 전용)
// [이유] 색+직접 라벨로 색맹 배려. 호재=빨강/악재=파랑 토큰 사용
// [사이드 임팩트] DisclosureAnalysis.similar_disclosures 데이터를 소비. Pro 미달 시 TierGate로 대체
// [수정 시 고려사항] ResponsiveContainer는 부모 너비에 맞춤. 데이터 없을 시 엠티 상태 표시.
//   차트 대체 텍스트(수치 요약)를 aria-label로 제공해 스크린리더 접근성 보장

import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Cell,
  ReferenceLine,
} from "recharts";
import type { SimilarDisclosure } from "@/lib/api/disclosures";

interface PriceReactionChartProps {
  data: SimilarDisclosure[];
}

export function PriceReactionChart({ data }: PriceReactionChartProps) {
  if (!data.length) {
    return (
      <p className="py-6 text-center text-sm text-muted-foreground">
        유사 사례 데이터가 없습니다.
      </p>
    );
  }

  const chartData = data.map((d) => ({
    label: d.rcept_dt.slice(5),
    reaction: d.price_reaction_5d_pct,
    corp: d.corp_name,
  }));

  const summary = data
    .map((d) => `${d.rcept_dt} ${d.price_reaction_5d_pct > 0 ? "+" : ""}${d.price_reaction_5d_pct.toFixed(1)}%`)
    .join(", ");

  return (
    <div
      aria-label={`유사 공시 5일 주가 반응: ${summary}`}
      role="img"
    >
      <ResponsiveContainer width="100%" height={160}>
        <BarChart data={chartData} margin={{ top: 8, right: 4, left: -16, bottom: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-border)" vertical={false} />
          <XAxis
            dataKey="label"
            tick={{ fontSize: 11, fill: "var(--color-muted-foreground)" }}
            tickLine={false}
            axisLine={false}
          />
          <YAxis
            tickFormatter={(v) => `${v}%`}
            tick={{ fontSize: 11, fill: "var(--color-muted-foreground)" }}
            tickLine={false}
            axisLine={false}
          />
          <Tooltip
            formatter={(v: number) => [`${v > 0 ? "+" : ""}${v.toFixed(1)}%`, "5일 반응"]}
            contentStyle={{
              borderRadius: 8,
              border: "1px solid var(--color-border)",
              fontSize: 12,
            }}
          />
          <ReferenceLine y={0} stroke="var(--color-border)" />
          <Bar dataKey="reaction" radius={[4, 4, 0, 0]} maxBarSize={40}>
            {chartData.map((entry, i) => (
              <Cell
                key={i}
                fill={
                  entry.reaction > 0
                    ? "var(--color-sentiment-positive)"
                    : "var(--color-sentiment-negative)"
                }
              />
            ))}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div className="mt-1 flex justify-center gap-4 text-[11px] text-muted-foreground">
        <span className="flex items-center gap-1">
          <span className="inline-block size-2.5 rounded-sm bg-[color:var(--color-sentiment-positive)]" aria-hidden />
          상승
        </span>
        <span className="flex items-center gap-1">
          <span className="inline-block size-2.5 rounded-sm bg-[color:var(--color-sentiment-negative)]" aria-hidden />
          하락
        </span>
      </div>
    </div>
  );
}
