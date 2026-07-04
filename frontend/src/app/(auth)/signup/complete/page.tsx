"use client";

// [목적] 가입 완료 화면(D10/m14) — 환영 메시지 + 시작 체크리스트로 첫 행동 유도
// [이유] "등록"·"설정" 버튼을 페이지 이탈 링크 대신 Sheet/Dialog로 전환해 온보딩 맥락 보존
// [사이드 임팩트] signupStore.clear()로 메모리 민감데이터 제거.
//   PortfolioSheet 성공 시 useCreatePortfolio 내부에서 ["portfolios"] 쿼리 자동 무효화.
//   NotifDialog 저장 시 enabled:true 강제 포함(온보딩 저장 = 알림 활성화 의도 확정 — Tech Review 리스크#2).
//   completeOnboarding 실패 시 onError toast — fire-and-forget 방지(온보딩 미완료로 남는 버그 대응).
//   sheetSide는 useSheetSide() 훅에서 관리 — matchMedia 로직 중복 없음.
// [수정 시 고려사항] portfolioDone·notifDone은 리로드 시 초기화 허용(온보딩 1회성).
//   퍼시스트 필요 시 usePortfolios/useNotificationSettings 초기값으로 derive 가능.
//   온보딩 완료 이벤트 로깅 추가 시 각 onSuccess 콜백에서 호출.
//   fetchMe() 제거 불가 — authStore.user는 null로 초기화, 닉네임 표시를 위해 최초 1회 필요.

import Link from "next/link";
import { type ElementType, type ReactNode, useEffect, useState } from "react";
import { toast } from "sonner";
import { useSheetSide, BOTTOM_SHEET_MIN_HEIGHT } from "@/hooks/useSheetSide";
import { ApiException } from "@/lib/api/client";
import { Check, Mail, MessageSquare, Smartphone } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { PortfolioSheet } from "@/components/domain/PortfolioSheet";
import { useAuthStore } from "@/lib/stores/authStore";
import { useSignupStore } from "@/lib/stores/signupStore";
import { usePortfolios } from "@/lib/api/portfolios";
import { useCompleteOnboarding } from "@/lib/api/auth";
import {
  useNotificationSettings,
  useUpdateNotificationSettings,
  type NotifChannel,
  type NotifFrequency,
  type NotificationSettings,
} from "@/lib/api/notifications";
import { cn } from "@/lib/utils";

// ─── NotifDialog ───────────────────────────────────────────────────────────

// [목적] 온보딩 알림 설정 Dialog — 채널·빈도 핵심 설정만 간소화해 PUT /notifications/settings 완료
// [이유] /notifications/settings 전체 페이지 진입 없이 온보딩에서 핵심 설정만 인라인 처리
// [사이드 임팩트] PUT 성공 시 useUpdateNotificationSettings 내부에서 ["notification-settings"] 쿼리 무효화.
//   enabled:true 강제 포함 — 온보딩 저장 의도는 알림 활성화 확정(Tech Review 리스크#2 해결).
//   type_filter·off_hours_allowed는 서버 기존값 그대로 전송(미노출 필드 의도치 않은 변경 방지).
// [수정 시 고려사항] TELEGRAM 채널은 Premium 전용 항상 disabled. Premium 도입 시 조건부 활성화.
//   Dialog 내 채널·빈도 변경만 처리 — 나머지는 "전체 설정 보기 →" 링크로 위임.

const DIALOG_CHANNELS: { value: NotifChannel; label: string; icon: ElementType; desc: string }[] = [
  { value: "KAKAO",    label: "카카오 알림톡", icon: MessageSquare, desc: "오픈율 40~60% · 즉시 발송" },
  { value: "EMAIL",    label: "이메일",         icon: Mail,          desc: "수신함에서 확인" },
  { value: "TELEGRAM", label: "텔레그램",       icon: Smartphone,    desc: "Premium 전용" },
];

const DIALOG_FREQUENCIES: { value: NotifFrequency; label: string }[] = [
  { value: "INSTANT", label: "즉시" },
  { value: "DAILY_1", label: "하루 1회" },
  { value: "DAILY_2", label: "하루 2회" },
  { value: "WEEKLY",  label: "주 1회" },
];

interface NotifDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onSuccess: () => void;
  settings: NotificationSettings | undefined;
  isSettingsLoading: boolean;
  settingsError: Error | null;
}

function NotifDialog({ open, onOpenChange, onSuccess, settings, isSettingsLoading, settingsError }: NotifDialogProps) {
  const [channel, setChannel] = useState<NotifChannel>("KAKAO");
  const [frequency, setFreq]  = useState<NotifFrequency>("INSTANT");
  const { mutate: updateSettings, isPending } = useUpdateNotificationSettings();

  // 서버 설정값으로 로컬 상태 초기화 (channel·frequency만 — 나머지는 PUT 시 기존값 그대로)
  useEffect(() => {
    if (!settings) return;
    setChannel(settings.channel);
    setFreq(settings.frequency);
  }, [settings]);

  const handleSave = () => {
    updateSettings(
      {
        channel,
        frequency,
        type_filter: settings?.type_filter ?? "ALL",
        off_hours_allowed: settings?.off_hours_allowed ?? true,
        enabled: true, // 온보딩 저장 = 알림 활성화 확정
      },
      {
        onSuccess: () => {
          onSuccess();
          onOpenChange(false);
        },
      },
    );
  };

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>알림 채널 설정</DialogTitle>
        </DialogHeader>

        {isSettingsLoading ? (
          <div className="py-8 text-center text-sm text-muted-foreground" role="status">
            설정을 불러오는 중...
          </div>
        ) : settingsError ? (
          <div className="py-8 text-center text-sm text-destructive" role="alert">
            {settingsError instanceof ApiException && settingsError.body.status === 401
              ? "인증이 만료되었습니다. 다시 로그인해주세요."
              : "설정을 불러오지 못했습니다. 잠시 후 다시 시도해주세요."}
          </div>
        ) : (
          <div className="flex flex-col gap-5">
            {/* 채널 선택 */}
            <section aria-labelledby="dlg-channel-heading">
              <h2 id="dlg-channel-heading" className="mb-2 text-[11px] font-extrabold uppercase tracking-widest text-primary">
                알림 채널
              </h2>
              <div className="flex flex-col gap-2" role="radiogroup" aria-labelledby="dlg-channel-heading">
                {DIALOG_CHANNELS.map(({ value, label, icon: Icon, desc }) => (
                  <button
                    key={value}
                    type="button"
                    role="radio"
                    aria-checked={channel === value}
                    onClick={() => setChannel(value)}
                    disabled={value === "TELEGRAM"}
                    className={cn(
                      "flex items-center gap-3 rounded-xl border-[1.5px] p-3 text-left transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:opacity-40",
                      channel === value
                        ? "border-primary bg-primary/5"
                        : "border-border bg-background hover:bg-muted",
                    )}
                  >
                    <span className={cn(
                      "grid size-8 shrink-0 place-items-center rounded-lg",
                      channel === value ? "bg-primary/10" : "bg-muted",
                    )}>
                      <Icon className={cn("size-4", channel === value ? "text-primary" : "text-muted-foreground")} aria-hidden />
                    </span>
                    <div className="flex-1">
                      <p className="text-sm font-bold text-foreground">{label}</p>
                      <p className="text-xs text-muted-foreground">{desc}</p>
                    </div>
                    <span
                      className={cn(
                        "grid size-4 shrink-0 place-items-center rounded-full border-2 transition-colors",
                        channel === value ? "border-primary" : "border-border",
                      )}
                      aria-hidden
                    >
                      {channel === value && <span className="size-2 rounded-full bg-primary" />}
                    </span>
                  </button>
                ))}
              </div>
            </section>

            {/* 발송 빈도 */}
            <section aria-labelledby="dlg-freq-heading">
              <h2 id="dlg-freq-heading" className="mb-2 text-[11px] font-extrabold uppercase tracking-widest text-primary">
                발송 빈도
              </h2>
              <div className="flex flex-wrap gap-2" role="radiogroup" aria-labelledby="dlg-freq-heading">
                {DIALOG_FREQUENCIES.map(({ value, label }) => (
                  <button
                    key={value}
                    type="button"
                    role="radio"
                    aria-checked={frequency === value}
                    onClick={() => setFreq(value)}
                    className={cn(
                      "rounded-full border-[1.5px] px-3 py-1.5 text-sm font-bold transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                      frequency === value
                        ? "border-primary bg-primary text-primary-foreground"
                        : "border-border bg-background text-muted-foreground hover:bg-muted",
                    )}
                  >
                    {label}
                  </button>
                ))}
              </div>
            </section>
          </div>
        )}

        <DialogFooter>
          <Link
            href="/notifications/settings"
            onClick={() => onOpenChange(false)}
            className="self-center text-xs text-muted-foreground hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            전체 설정 보기 →
          </Link>
          <Button
            onClick={handleSave}
            disabled={isPending || isSettingsLoading || settingsError !== null}
          >
            {isPending ? "저장 중..." : "저장"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

// ─── CompletePage ──────────────────────────────────────────────────────────

export default function CompletePage() {
  const { user, fetchMe } = useAuthStore();
  const { clear } = useSignupStore();
  const { mutate: completeOnboarding } = useCompleteOnboarding();

  const [portfolioSheetOpen, setPortfolioSheetOpen] = useState(false);
  const [notifDialogOpen, setNotifDialogOpen]       = useState(false);
  const [notifDone, setNotifDone]                   = useState(false);
  const sheetSide = useSheetSide();

  const { data: existingPortfolios } = usePortfolios();
  // 이미 등록된 종목이 있으면 체크리스트를 완료 상태로 초기화 (페이지 새로고침 후 재등록 방지)
  const portfolioDone = (existingPortfolios?.length ?? 0) > 0;

  // CompletePage 최상단 훅 호출 — Dialog 오픈 전 데이터 준비(loading race 방지)
  const { data: notifSettings, isLoading: isNotifLoading, error: notifError } = useNotificationSettings();

  useEffect(() => {
    clear();
    // fetchMe: authStore.user는 null로 초기화되므로 닉네임 표시를 위해 최초 1회 필요
    fetchMe();
    // 온보딩 완료 마킹 — onboarding_completed_at 설정 → JWT claim 갱신 필요.
    // 성공 후 /api/auth/refresh를 await: 새 토큰(onboarding_completed=true)이 쿠키에 저장된 뒤
    // 사용자가 CTA 버튼을 눌러 /dashboard로 이동해야 middleware 게이트 통과.
    // refresh를 await하지 않으면 onSuccess → CTA 클릭 경쟁 조건 발생 — 구 토큰으로 /signup/terms/oauth 튕김.
    // refresh 실패는 무시(catch) — backward-compat 처리로 구 토큰도 30분 내 자동 갱신됨.
    completeOnboarding(undefined, {
      onSuccess: async () => {
        await fetch("/api/auth/refresh", { method: "POST" }).catch(() => {});
      },
      onError: () => toast.error("온보딩 처리 중 오류가 발생했습니다. 페이지를 새로고침해주세요."),
    });
  // clear·fetchMe·completeOnboarding은 모두 안정적 참조(Zustand action / TanStack mutate)
  }, [clear, fetchMe, completeOnboarding]);

  const nickname = user?.nickname ?? "투자자";

  const [navigatingToDashboard, setNavigatingToDashboard] = useState(false);

  // [목적] 대시보드 이동 전 JWT 토큰 강제 갱신 후 전체 네비게이션.
  // [이유] 온보딩 완료(onboarding_completed_at) 후 새 JWT(onboarding_completed=true)가 dr_session 쿠키에
  //   반영돼야 middleware 온보딩 게이트를 통과한다. 기존 <Link href>는 useEffect refresh가 끝나기 전(프로덕션
  //   지연 시 수십 초) 클릭하면 구 토큰(onboarding_completed=false)으로 이동해 /signup/terms/oauth로 튕기는
  //   경쟁 조건이 있었다. 클릭 시점에 refresh를 await → 갱신된 쿠키 확보 후 window.location으로 전체 이동.
  const goDashboard = async () => {
    setNavigatingToDashboard(true);
    await fetch("/api/auth/refresh", { method: "POST" }).catch(() => {});
    window.location.assign("/dashboard");
  };

  return (
    <>
      <div className="flex min-h-screen items-start justify-center bg-muted/30 px-6 py-12 md:py-20">
        <div className="flex w-full max-w-[540px] flex-col items-center gap-7 text-center">
          {/* 체크 아이콘 — 온보딩 완료 성공 표시: sentiment-positive(주식 빨강)가 아닌 primary(파란색)로 범용 성공 의미 전달 */}
          <div
            className="grid size-24 place-items-center rounded-full bg-primary"
            aria-hidden
          >
            <Check className="size-11 text-white" strokeWidth={2.8} />
          </div>

          <div>
            <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
              환영합니다,<br />{nickname}님!
            </h1>
            <p className="mt-3 text-base text-muted-foreground">
              이제 보유 종목만 등록하면 공시 해석이<br />카카오 알림톡과 웹 대시보드로 도착해요.
            </p>
          </div>

          {/* 시작 체크리스트 */}
          <div className="w-full rounded-2xl border border-border bg-card p-5 text-left">
            <p className="mb-4 text-sm font-extrabold text-foreground">시작 체크리스트</p>
            <ul className="flex flex-col gap-3">
              <ChecklistItem done label="계정 만들기" sub="완료" />
              <ChecklistItem
                done={portfolioDone}
                label="보유 종목 등록"
                sub="3개까지 무료"
                action={
                  <Button size="sm" className="shrink-0" aria-label="보유 종목 등록" onClick={() => setPortfolioSheetOpen(true)}>
                    등록
                  </Button>
                }
              />
              <ChecklistItem
                done={notifDone}
                label="알림 채널 설정"
                sub="카카오 알림톡"
                action={
                  <Button variant="outline" size="sm" className="shrink-0" aria-label="알림 채널 설정" onClick={() => setNotifDialogOpen(true)}>
                    설정
                  </Button>
                }
              />
            </ul>
          </div>

          {/* 하단 CTA — 종목 등록 완료 후 대시보드로 전환 */}
          {portfolioDone ? (
            <button
              type="button"
              onClick={goDashboard}
              disabled={navigatingToDashboard}
              className={buttonVariants({ size: "lg" }) + " w-full max-w-xs"}
            >
              {navigatingToDashboard ? "이동 중…" : "대시보드로 이동 →"}
            </button>
          ) : (
            <button
              type="button"
              aria-label="첫 종목 등록하기"
              onClick={() => setPortfolioSheetOpen(true)}
              className={buttonVariants({ size: "lg" }) + " w-full max-w-xs"}
            >
              첫 종목 등록하기 →
            </button>
          )}

          <button
            type="button"
            onClick={goDashboard}
            disabled={navigatingToDashboard}
            className="text-sm text-muted-foreground hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring disabled:opacity-50"
          >
            대시보드로 바로 이동
          </button>
        </div>
      </div>

      <PortfolioSheet
        open={portfolioSheetOpen}
        onOpenChange={setPortfolioSheetOpen}
        side={sheetSide}
        contentClassName={sheetSide === "bottom" ? BOTTOM_SHEET_MIN_HEIGHT : undefined}
        onSuccess={() => {}}
      />
      <NotifDialog
        open={notifDialogOpen}
        onOpenChange={setNotifDialogOpen}
        onSuccess={() => setNotifDone(true)}
        settings={notifSettings}
        isSettingsLoading={isNotifLoading}
        settingsError={notifError}
      />
    </>
  );
}

// ─── ChecklistItem ─────────────────────────────────────────────────────────

function ChecklistItem({
  done,
  label,
  sub,
  action,
}: {
  done: boolean;
  label: string;
  sub: string;
  action?: ReactNode;
}) {
  return (
    <li className="flex items-center gap-3" aria-label={`${label}${done ? ", 완료" : ""}`}>
      <span
        className={`grid size-6 shrink-0 place-items-center rounded-[7px] border-2 ${done ? "border-primary bg-primary" : "border-border"}`}
        aria-hidden
      >
        {done && <Check className="size-3.5 text-white" strokeWidth={2.8} />}
      </span>
      <span className="flex-1">
        <span className="block text-sm font-bold text-foreground">{label}</span>
        <span className="block text-xs text-muted-foreground">{sub}</span>
      </span>
      {!done && action}
    </li>
  );
}
