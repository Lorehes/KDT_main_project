"use client";

// [목적] AI 분석 정확도 평가 수집 — "도움됨/부정확" + 오류 유형 + 의견
// [이유] 분석 품질 루프 데이터 수집. 모든 공시 상세 화면에 면책 고지와 함께 상시 노출 의무
// [사이드 임팩트] POST /analyses/{id}/feedback 호출. 동일 (user, analysis) 재투표는 UPDATE
// [수정 시 고려사항] reason 최대 2000자 서버 제한. 제출 후 토스트 알림 추가 예정(W4)

import { useState } from "react";
import { ThumbsUp, ThumbsDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useFeedbackMutation } from "@/lib/api/disclosures";
import { cn } from "@/lib/utils";

const ERROR_TYPES = [
  "호재/악재 판정 오류",
  "요약 내용 부정확",
  "계약 상대방 오류",
  "수치·날짜 오류",
  "기타",
];

interface FeedbackPromptProps {
  analysisId: number;
  className?: string;
}

export function FeedbackPrompt({ analysisId, className }: FeedbackPromptProps) {
  const [verdict, setVerdict] = useState<"USEFUL" | "INACCURATE" | null>(null);
  const [chips, setChips] = useState<string[]>([]);
  const [reason, setReason] = useState("");
  const [submitted, setSubmitted] = useState(false);

  const { mutate, isPending } = useFeedbackMutation(analysisId);

  const toggleChip = (c: string) =>
    setChips((prev) => (prev.includes(c) ? prev.filter((x) => x !== c) : [...prev, c]));

  const submit = () => {
    if (!verdict) return;
    const fullReason = [chips.join(", "), reason].filter(Boolean).join(" / ");
    mutate(
      { verdict, reason: fullReason || undefined },
      { onSuccess: () => setSubmitted(true) },
    );
  };

  if (submitted) {
    return (
      <p className="text-sm font-semibold text-muted-foreground" role="status">
        ✓ 피드백이 전달되었습니다. 분석 개선에 반영됩니다.
      </p>
    );
  }

  return (
    <section className={cn("flex flex-col gap-4", className)} aria-label="분석 피드백">
      <p className="text-sm font-semibold text-muted-foreground">이 분석이 도움이 되었나요?</p>

      <div className="flex gap-3">
        {(["USEFUL", "INACCURATE"] as const).map((v) => (
          <button
            key={v}
            type="button"
            onClick={() => setVerdict(v)}
            className={cn(
              "flex flex-1 items-center justify-center gap-2 rounded-xl border-[1.5px] px-5 py-3 transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              verdict === v && v === "USEFUL" && "border-[color:var(--color-sentiment-positive)] bg-[color:var(--color-sentiment-positive)]/10",
              verdict === v && v === "INACCURATE" && "border-destructive bg-destructive/10",
              verdict !== v && "border-border bg-card",
            )}
            aria-pressed={verdict === v}
          >
            {v === "USEFUL" ? (
              <ThumbsUp className="size-5 text-[color:var(--color-sentiment-positive)]" aria-hidden />
            ) : (
              <ThumbsDown className="size-5 text-destructive" aria-hidden />
            )}
            <span className="text-sm font-extrabold text-foreground">
              {v === "USEFUL" ? "도움됨" : "부정확"}
            </span>
          </button>
        ))}
      </div>

      {verdict === "INACCURATE" && (
        <>
          <div className="flex flex-wrap gap-2" role="group" aria-label="오류 유형 선택">
            {ERROR_TYPES.map((c) => (
              <button
                key={c}
                type="button"
                onClick={() => toggleChip(c)}
                className={cn(
                  "rounded-full border-[1.5px] px-4 py-2 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring",
                  chips.includes(c)
                    ? "border-foreground bg-foreground text-background"
                    : "border-border bg-card text-muted-foreground",
                )}
                aria-pressed={chips.includes(c)}
              >
                {c}
              </button>
            ))}
          </div>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value.slice(0, 2000))}
            placeholder="추가 의견 (선택, 최대 2000자)"
            rows={3}
            className="w-full resize-none rounded-xl border border-border bg-background px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label="추가 의견"
          />
        </>
      )}

      {verdict && (
        <Button
          onClick={submit}
          disabled={isPending}
          size="sm"
          className="self-start"
        >
          {isPending ? "전송 중..." : "피드백 제출"}
        </Button>
      )}
    </section>
  );
}
