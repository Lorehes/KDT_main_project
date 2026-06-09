// [목적] AI 분석 신뢰도 0~1 시각화 + 판단보류 임계 처리
// [이유] 신뢰도 낮으면 "판단 보류" 표시 — 투자자 보호 의무(CLAUDE.md §6-6)
// [사이드 임팩트] SentimentBadge와 함께 공시 상세에 표시. confidence < THRESHOLD 시 SentimentBadge도 WITHHELD
// [수정 시 고려사항] 임계값(WITHHELD_THRESHOLD)은 백엔드 is_withheld 필드와 일관성 유지 필요.
//   현재 클라이언트 0.5 사용 — api_spec 확인 후 조정

import { Progress } from "@/components/ui/progress";

const WITHHELD_THRESHOLD = 0.5;

interface ConfidenceMeterProps {
  confidence: number;
  showLabel?: boolean;
  className?: string;
}

export function ConfidenceMeter({
  confidence,
  showLabel = true,
  className,
}: ConfidenceMeterProps) {
  const pct = Math.round(confidence * 100);
  const isLow = confidence < WITHHELD_THRESHOLD;

  return (
    <div className={`flex items-center gap-2.5 ${className ?? ""}`}>
      <Progress
        value={pct}
        className="h-2 w-32"
        aria-label={`AI 분석 신뢰도 ${pct}%`}
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
      />
      {showLabel && (
        <span className={`text-xs font-semibold ${isLow ? "text-[color:var(--color-sentiment-withheld)]" : "text-muted-foreground"}`}>
          {isLow ? "신뢰도 낮음" : `신뢰도 ${pct}%`}
        </span>
      )}
    </div>
  );
}
