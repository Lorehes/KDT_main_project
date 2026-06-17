"use client";

// [목적] 가입 완료 화면(D10/m14) — 환영 메시지 + 시작 체크리스트로 첫 행동 유도
// [이유] "등록"·"설정" 버튼을 페이지 이탈 링크 대신 Sheet/Dialog로 전환해 온보딩 맥락 보존
// [사이드 임팩트] signupStore.clear()로 메모리 민감데이터 제거.
//   PortfolioSheet 성공 시 useCreatePortfolio 내부에서 ["portfolios"] 쿼리 자동 무효화.
//   NotifDialog 저장 시 enabled:true 강제 포함(온보딩 저장 = 알림 활성화 의도 확정 — Tech Review 리스크#2).
// [수정 시 고려사항] portfolioDone·notifDone은 리로드 시 초기화 허용(온보딩 1회성).
//   퍼시스트 필요 시 usePortfolios/useNotificationSettings 초기값으로 derive 가능.
//   온보딩 완료 이벤트 로깅 추가 시 각 onSuccess 콜백에서 호출.

import Link from "next/link";
import { type ElementType, type ReactNode, useEffect, useState } from "react";
import { Check, ChevronLeft, Mail, MessageSquare, Smartphone } from "lucide-react";
import { useForm } from "react-hook-form";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { StockSearchCombobox } from "@/components/domain/StockSearchCombobox";
import { useAuthStore } from "@/lib/stores/authStore";
import { useSignupStore } from "@/lib/stores/signupStore";
import { useCreatePortfolio } from "@/lib/api/portfolios";
import {
  useNotificationSettings,
  useUpdateNotificationSettings,
  type NotifChannel,
  type NotifFrequency,
  type NotificationSettings,
} from "@/lib/api/notifications";
import { ApiException } from "@/lib/api/client";
import { API_ERROR_CODES } from "@/lib/api/errorCodes";
import { cn } from "@/lib/utils";
import type { StockSearchResult } from "@/lib/api/stocks";

// ─── PortfolioSheet ────────────────────────────────────────────────────────

// [목적] 온보딩 종목 등록 Sheet — 2-step(검색→정보입력)으로 페이지 이탈 없이 POST /portfolios 완료
// [이유] portfolios/new 페이지 진입 없이 온보딩 맥락 내에서 첫 종목 등록 → 이탈률 최소화
// [사이드 임팩트] mutateAsync 성공 시 useCreatePortfolio 내부에서 ["portfolios"] 쿼리 무효화.
//   avg_buy_price·quantity 절대 console.log 금지(CLAUDE.md §7 금융 개인정보).
// [수정 시 고려사항] Free 3종목 422·중복 409 에러 분기는 portfolios/new/page.tsx 패턴과 동일하게 유지.
//   Sheet 닫힘(onOpenChange false) 시 selectedStock·form 상태 자동 초기화 → 다음 오픈 시 Step1 시작.

interface PortfolioSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  side: "bottom" | "right";
  onSuccess: () => void;
}

interface PortfolioFormValues {
  avg_buy_price: string;
  quantity: string;
}

function PortfolioSheet({ open, onOpenChange, side, onSuccess }: PortfolioSheetProps) {
  const [selectedStock, setSelectedStock] = useState<StockSearchResult | null>(null);
  const { mutateAsync, isPending } = useCreatePortfolio();

  const {
    register,
    handleSubmit,
    setError,
    reset,
    formState: { errors },
  } = useForm<PortfolioFormValues>({ defaultValues: { avg_buy_price: "", quantity: "" } });

  const handleOpenChange = (next: boolean) => {
    if (!next) {
      setSelectedStock(null);
      reset();
    }
    onOpenChange(next);
  };

  const onSubmit = async (data: PortfolioFormValues) => {
    if (!selectedStock) return;
    const body = {
      stock_code: selectedStock.stock_code,
      avg_buy_price: Number(data.avg_buy_price),
      quantity: Number(data.quantity),
    };
    // avg_buy_price·quantity 절대 console.log 금지 — 금융 개인정보(CLAUDE.md §7)
    try {
      await mutateAsync(body);
      onSuccess();
      onOpenChange(false);
    } catch (e) {
      if (e instanceof ApiException) {
        if (e.body.code === API_ERROR_CODES.BUSINESS_RULE_VIOLATION) {
          setError("root", { message: "Free 플랜은 최대 3종목까지 등록 가능합니다. Pro로 업그레이드해주세요." });
        } else if (e.body.code === API_ERROR_CODES.DUPLICATE_RESOURCE) {
          setError("root", { message: "이미 등록된 종목입니다." });
        } else {
          setError("root", { message: e.body.message ?? "등록에 실패했습니다." });
        }
      } else {
        setError("root", { message: "네트워크 오류가 발생했습니다. 다시 시도해주세요." });
      }
    }
  };

  return (
    <Sheet open={open} onOpenChange={handleOpenChange}>
      <SheetContent side={side} className="flex flex-col overflow-y-auto">
        <SheetHeader>
          <SheetTitle>보유 종목 등록</SheetTitle>
        </SheetHeader>

        {/* Step 1 — 종목 검색 */}
        {!selectedStock && (
          <div className="flex flex-1 flex-col gap-4 p-4">
            <p className="text-sm text-muted-foreground">종목명 또는 코드로 검색하세요.</p>
            <StockSearchCombobox onSelect={(stock) => setSelectedStock(stock)} />
          </div>
        )}

        {/* Step 2 — 정보 입력 */}
        {selectedStock && (
          <form onSubmit={handleSubmit(onSubmit)} className="flex flex-1 flex-col" noValidate>
            <div className="flex flex-1 flex-col gap-5 p-4">
              {/* 종목 헤더 */}
              <div className="flex items-center gap-3">
                <div
                  className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary text-sm font-extrabold text-primary-foreground"
                  aria-hidden
                >
                  {selectedStock.corp_name.slice(0, 2)}
                </div>
                <div>
                  <p className="text-base font-extrabold text-foreground">{selectedStock.corp_name}</p>
                  <p className="font-mono text-sm text-muted-foreground">{selectedStock.stock_code}</p>
                </div>
              </div>

              {errors.root && (
                <p className="rounded-lg bg-destructive/10 px-4 py-2.5 text-sm text-destructive" role="alert">
                  {errors.root.message}
                  {errors.root.message?.includes("Pro") && (
                    <> <Link href="/pricing" className="font-bold underline">요금제 보기</Link></>
                  )}
                </p>
              )}

              {/* 매수 평균가 */}
              <div className="flex flex-col gap-1.5">
                <label htmlFor="sheet-avg-price" className="text-sm font-semibold text-foreground">
                  매수 평균가 <span className="text-destructive" aria-hidden>*</span>
                </label>
                <div
                  className={cn(
                    "flex items-center gap-2 rounded-xl border bg-background px-4 py-3 focus-within:ring-2 focus-within:ring-primary/20",
                    errors.avg_buy_price
                      ? "border-destructive focus-within:border-destructive"
                      : "border-border focus-within:border-primary",
                  )}
                >
                  <input
                    id="sheet-avg-price"
                    type="number"
                    inputMode="decimal"
                    min="1"
                    step="1"
                    placeholder="예: 75000"
                    autoComplete="off"
                    aria-required="true"
                    aria-describedby="sheet-price-hint"
                    className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                    {...register("avg_buy_price", {
                      required: "매수 평균가를 입력해주세요",
                      min: { value: 1, message: "1 이상이어야 합니다" },
                    })}
                  />
                  <span className="shrink-0 text-sm text-muted-foreground">원</span>
                </div>
                {errors.avg_buy_price && (
                  <p className="text-xs text-destructive" role="alert">{errors.avg_buy_price.message}</p>
                )}
                <p id="sheet-price-hint" className="text-xs text-muted-foreground">손익 계산에 사용됩니다.</p>
              </div>

              {/* 보유 수량 */}
              <div className="flex flex-col gap-1.5">
                <label htmlFor="sheet-quantity" className="text-sm font-semibold text-foreground">
                  보유 수량 <span className="text-destructive" aria-hidden>*</span>
                </label>
                <div
                  className={cn(
                    "flex items-center gap-2 rounded-xl border bg-background px-4 py-3 focus-within:ring-2 focus-within:ring-primary/20",
                    errors.quantity
                      ? "border-destructive focus-within:border-destructive"
                      : "border-border focus-within:border-primary",
                  )}
                >
                  <input
                    id="sheet-quantity"
                    type="number"
                    inputMode="numeric"
                    min="1"
                    step="1"
                    placeholder="예: 10"
                    autoComplete="off"
                    aria-required="true"
                    aria-describedby="sheet-quantity-hint"
                    className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                    {...register("quantity", {
                      required: "보유 수량을 입력해주세요",
                      min: { value: 1, message: "1 이상이어야 합니다" },
                      validate: (v) => Number.isInteger(Number(v)) || "정수를 입력해주세요",
                    })}
                  />
                  <span className="shrink-0 text-sm text-muted-foreground">주</span>
                </div>
                {errors.quantity && (
                  <p className="text-xs text-destructive" role="alert">{errors.quantity.message}</p>
                )}
                <p id="sheet-quantity-hint" className="text-xs text-muted-foreground">주(株) 단위 정수로 입력해주세요.</p>
              </div>
            </div>

            {/* 액션 영역 */}
            <div className="flex items-center gap-3 border-t border-border p-4">
              <button
                type="button"
                onClick={() => { setSelectedStock(null); reset(); }}
                className="flex items-center gap-1 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <ChevronLeft className="size-4" aria-hidden />
                다시 검색
              </button>
              <Button type="submit" disabled={isPending} className="ml-auto">
                {isPending ? "저장 중..." : "저장"}
              </Button>
            </div>
          </form>
        )}
      </SheetContent>
    </Sheet>
  );
}

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
  isSettingsError: boolean;
}

function NotifDialog({ open, onOpenChange, onSuccess, settings, isSettingsLoading, isSettingsError }: NotifDialogProps) {
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
        ) : isSettingsError ? (
          <div className="py-8 text-center text-sm text-destructive" role="alert">
            설정을 불러오지 못했습니다. 페이지를 새로고침해주세요.
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
            disabled={isPending || isSettingsLoading || isSettingsError}
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

  const [portfolioSheetOpen, setPortfolioSheetOpen] = useState(false);
  const [portfolioDone, setPortfolioDone]           = useState(false);
  const [notifDialogOpen, setNotifDialogOpen]       = useState(false);
  const [notifDone, setNotifDone]                   = useState(false);
  const [sheetSide, setSheetSide]                   = useState<"bottom" | "right">("bottom");

  // CompletePage 최상단 훅 호출 — Dialog 오픈 전 데이터 준비(loading race 방지)
  const { data: notifSettings, isLoading: isNotifLoading, isError: isNotifError } = useNotificationSettings();

  useEffect(() => {
    clear();
    fetchMe();
  // clear·fetchMe는 안정적 참조
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 뷰포트 폭에 따라 Sheet side 변경 — sm(640px) 이상은 right, 미만은 bottom
  useEffect(() => {
    const mq = window.matchMedia("(min-width: 640px)");
    const update = () => setSheetSide(mq.matches ? "right" : "bottom");
    update();
    mq.addEventListener("change", update);
    return () => mq.removeEventListener("change", update);
  }, []);

  const nickname = user?.nickname ?? "투자자";

  return (
    <>
      <div className="flex min-h-screen items-start justify-center bg-muted/30 px-6 py-12 md:items-center">
        <div className="flex w-full max-w-[540px] flex-col items-center gap-7 text-center">
          {/* 체크 아이콘 */}
          <div
            className="grid size-24 place-items-center rounded-full bg-[color:var(--color-sentiment-positive)]"
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
            <Link
              href="/dashboard"
              className={buttonVariants({ size: "lg" }) + " w-full max-w-xs"}
            >
              대시보드로 이동 →
            </Link>
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

          <Link
            href="/dashboard"
            className="text-sm text-muted-foreground hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
          >
            대시보드로 바로 이동
          </Link>
        </div>
      </div>

      <PortfolioSheet
        open={portfolioSheetOpen}
        onOpenChange={setPortfolioSheetOpen}
        side={sheetSide}
        onSuccess={() => setPortfolioDone(true)}
      />
      <NotifDialog
        open={notifDialogOpen}
        onOpenChange={setNotifDialogOpen}
        onSuccess={() => setNotifDone(true)}
        settings={notifSettings}
        isSettingsLoading={isNotifLoading}
        isSettingsError={isNotifError}
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
