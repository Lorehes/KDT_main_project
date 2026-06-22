"use client";

// [목적] 알림 설정 페이지(D14/m06) + 카카오 알림톡 미리보기(D25/m08) — 채널·유형·야간 설정
// [이유] 사용자가 언제·어떤 채널로·어떤 유형의 공시 알림을 받을지 제어하는 핵심 설정 화면
// [사이드 임팩트] PUT /notifications/settings 성공 후 ["notification-settings"] 쿼리 무효화
// [수정 시 고려사항] 알림 채널 enum: KAKAO|TELEGRAM|EMAIL. 빈도: INSTANT|DAILY_1|DAILY_2|WEEKLY.
//   off_hours_allowed는 거래시간외(22시~8시) 알림 허용 여부. 미리보기는 정적 mockup

import { useEffect, useState } from "react";
import { Bell, MessageSquare, Mail, Smartphone, Clock } from "lucide-react";
import {
  useNotificationSettings,
  useUpdateNotificationSettings,
  type NotifChannel,
  type NotifFrequency,
  type NotifTypeFilter,
} from "@/lib/api/notifications";
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import { Switch } from "@/components/ui/switch";
import { cn } from "@/lib/utils";

// comingSoon: 채널 선택은 가능하나 BE 발송 미구현(MVP) — '곧 지원 예정' 배지로 오인 방지(텔레그램 Bot API 연동 후속).
const CHANNELS: { value: NotifChannel; label: string; icon: React.ElementType; desc: string; comingSoon?: boolean }[] = [
  { value: "KAKAO",    label: "카카오 알림톡", icon: MessageSquare, desc: "즉시 발송" },
  { value: "EMAIL",    label: "이메일",         icon: Mail,          desc: "수신함에서 확인" },
  { value: "TELEGRAM", label: "텔레그램",       icon: Smartphone,    desc: "텔레그램 봇으로 수신", comingSoon: true },
];

const FREQUENCIES: { value: NotifFrequency; label: string }[] = [
  { value: "INSTANT", label: "즉시" },
  { value: "DAILY_1", label: "하루 1회" },
  { value: "DAILY_2", label: "하루 2회" },
  { value: "WEEKLY",  label: "주 1회" },
];

const TYPE_FILTERS: { value: NotifTypeFilter; label: string }[] = [
  { value: "ALL",           label: "전체" },
  { value: "POSITIVE_ONLY", label: "호재만" },
  { value: "NEGATIVE_ONLY", label: "악재만" },
];

export default function NotificationSettingsPage() {
  const { data: settings, isLoading } = useNotificationSettings();
  const { mutate: updateSettings, isPending } = useUpdateNotificationSettings();

  const [channel, setChannel]   = useState<NotifChannel>("KAKAO");
  const [frequency, setFreq]    = useState<NotifFrequency>("INSTANT");
  const [typeFilter, setFilter] = useState<NotifTypeFilter>("ALL");
  const [offHours, setOffHours] = useState(true);
  const [showPreview, setShowPreview] = useState(false);

  // 서버 설정 로드 시 로컬 상태 동기화
  useEffect(() => {
    if (!settings) return;
    setChannel(settings.channel);
    setFreq(settings.frequency);
    setFilter(settings.type_filter);
    setOffHours(settings.off_hours_allowed);
  }, [settings]);

  const handleSave = () => {
    updateSettings({ channel, frequency: frequency, type_filter: typeFilter, off_hours_allowed: offHours });
  };

  if (isLoading) {
    return <div className="py-12 text-center text-sm text-muted-foreground" role="status">설정을 불러오는 중...</div>;
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <div>
        <h1 className="text-2xl font-extrabold tracking-tight text-foreground">알림 설정</h1>
        <p className="mt-1 text-sm text-muted-foreground">언제·무엇을·어떤 채널로 받을지 설정하세요.</p>
      </div>

      {/* 채널 선택 */}
      <section aria-labelledby="channel-heading" className="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h2 id="channel-heading" className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">알림 채널</h2>
        <div className="flex flex-col gap-2" role="radiogroup" aria-labelledby="channel-heading">
          {CHANNELS.map(({ value, label, icon: Icon, desc, comingSoon }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={channel === value}
              onClick={() => setChannel(value)}
              className={cn(
                "flex items-center gap-3 rounded-xl border-[1.5px] p-4 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                channel === value ? "border-primary bg-primary/5" : "border-border bg-background hover:bg-muted",
              )}
            >
              <span className={cn("grid size-9 place-items-center rounded-lg", channel === value ? "bg-primary/10" : "bg-muted")}>
                <Icon className={cn("size-4", channel === value ? "text-primary" : "text-muted-foreground")} aria-hidden />
              </span>
              <div className="flex-1">
                <p className="flex items-center gap-1.5 text-sm font-bold text-foreground">
                  {label}
                  {comingSoon && (
                    <span className="rounded-md bg-muted px-1.5 py-0.5 text-[10px] font-bold text-muted-foreground">
                      곧 지원 예정
                    </span>
                  )}
                </p>
                <p className="text-xs text-muted-foreground">{desc}</p>
              </div>
              <span className={cn("grid size-5 place-items-center rounded-full border-2 transition-colors", channel === value ? "border-primary" : "border-border")} aria-hidden>
                {channel === value && <span className="size-2.5 rounded-full bg-primary" />}
              </span>
            </button>
          ))}
        </div>

        {channel === "KAKAO" && (
          <button
            type="button"
            onClick={() => setShowPreview((v) => !v)}
            className="mt-3 text-xs font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            {showPreview ? "미리보기 닫기" : "알림톡 미리보기 →"}
          </button>
        )}

        {/* D25/m08 — 알림톡 미리보기 */}
        {showPreview && channel === "KAKAO" && (
          <div className="mt-4" aria-label="카카오 알림톡 미리보기">
            <KakaoPreview />
          </div>
        )}
      </section>

      {/* 발송 빈도 */}
      <section aria-labelledby="freq-heading" className="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h2 id="freq-heading" className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">발송 빈도</h2>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-labelledby="freq-heading">
          {FREQUENCIES.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={frequency === value}
              onClick={() => setFreq(value)}
              className={cn(
                "rounded-full border-[1.5px] px-4 py-2 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                frequency === value ? "border-primary bg-primary text-primary-foreground" : "border-border bg-background text-muted-foreground hover:bg-muted",
              )}
            >
              {label}
            </button>
          ))}
        </div>
      </section>

      {/* 알림 유형 필터 */}
      <section aria-labelledby="type-heading" className="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <h2 id="type-heading" className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">알림 유형</h2>
        <div className="flex flex-wrap gap-2" role="radiogroup" aria-labelledby="type-heading">
          {TYPE_FILTERS.map(({ value, label }) => (
            <button
              key={value}
              type="button"
              role="radio"
              aria-checked={typeFilter === value}
              aria-label={`${label} 알림`}
              onClick={() => setFilter(value)}
              className={cn(
                "shrink-0 rounded-full transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                value === "ALL"
                  ? cn(
                      "border-[1.5px] px-4 py-2 text-sm font-bold",
                      typeFilter === value
                        ? "border-primary bg-primary text-primary-foreground"
                        : "border-border bg-background text-muted-foreground hover:bg-muted",
                    )
                  : cn(
                      "hover:opacity-90",
                      typeFilter === value ? "ring-2 ring-offset-2 ring-offset-background ring-ring" : "",
                    ),
              )}
            >
              {value === "ALL" ? (
                label
              ) : (
                <SentimentBadge
                  sentiment={value === "POSITIVE_ONLY" ? "POSITIVE" : "NEGATIVE"}
                  size="lg"
                  className="pointer-events-none"
                />
              )}
            </button>
          ))}
        </div>
      </section>

      {/* 야간 발송 허용 */}
      <section aria-labelledby="offhours-heading" className="rounded-2xl border border-border bg-card p-5 shadow-sm">
        <div className="flex items-center justify-between">
          <div className="flex items-center gap-3">
            <span className="grid size-9 place-items-center rounded-lg bg-muted">
              <Clock className="size-4 text-muted-foreground" aria-hidden />
            </span>
            <div>
              <h2 id="offhours-heading" className="text-sm font-bold text-foreground">야간 발송 허용</h2>
              <p className="text-xs text-muted-foreground">22시 이후 ~ 익일 8시 발송 허용</p>
            </div>
          </div>
          <Switch
            checked={offHours}
            onCheckedChange={(checked) => setOffHours(checked)}
            aria-labelledby="offhours-heading"
          />
        </div>
      </section>

      {/* 저장 버튼 */}
      <button
        type="button"
        onClick={handleSave}
        disabled={isPending}
        className={cn(
          "w-full rounded-xl py-3.5 text-sm font-extrabold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
          "bg-primary text-primary-foreground hover:bg-primary/90 disabled:opacity-50",
        )}
      >
        {isPending ? "저장 중..." : "설정 저장"}
      </button>
    </div>
  );
}

// D25/m08 — 카카오 알림톡 미리보기 (정적 mockup)
function KakaoPreview() {
  return (
    <div className="overflow-hidden rounded-2xl bg-[color:var(--color-brand-navy)] p-3" aria-label="카카오 알림톡 미리보기">
      <div className="rounded-xl bg-[#B2C7D4] p-3">
        <div className="mb-3 flex items-center gap-2 rounded-t-lg bg-[#A6C8DA] px-3 py-2.5 text-sm font-bold text-[#1A2A33]">
          <span className="text-lg">←</span>
          공시레이더
        </div>
        <div className="rounded-b-xl bg-white p-4 shadow-sm">
          <span className="mb-2 inline-block rounded bg-[#3C1E1E] px-1.5 py-0.5 text-[10px] font-extrabold text-white">
            공시레이더
          </span>
          <p className="mb-1 text-sm font-extrabold text-[color:var(--color-brand-navy)]">삼성전자 (005930)</p>
          <p className="mb-3 text-sm leading-relaxed text-foreground">
            <span className="font-bold text-[color:var(--color-sentiment-negative)]">▼ 악재</span> 단일판매·공급계약 해지<br />
            전환사채 1,000억 발행으로 주식 추가 발행 가능성이 있어 주가에 부정적인 영향이 예상됩니다.
          </p>
          <button type="button" className="mb-3 w-full rounded-lg bg-[color:var(--color-brand-blue)] py-2.5 text-sm font-bold text-white">
            자세히 보기
          </button>
          <p className="border-t border-dashed border-gray-200 pt-2 text-[10px] leading-relaxed text-gray-400">
            본 분석은 정보 제공용이며 투자 자문·권유가 아닙니다. AI 분석은 부정확할 수 있습니다.
          </p>
        </div>
      </div>
    </div>
  );
}
