"use client";

// [목적] 종목 등록 상세 화면 — 보유 정보(평균 매수가·수량) + 알림 받을 공시 종류 선택
// [이유] 매수 정보와 알림 선호를 한 화면에서 설정해 등록 흐름 단축. 2열 레이아웃으로 정보 밀도 향상.
// [사이드 임팩트] POST /portfolios 호출 후 ["portfolios"] 쿼리 무효화 → 목록 자동 갱신.
//   매수가·수량은 평문 console.log 절대 금지 — 백엔드에서 AES-256-GCM 암호화 저장(CLAUDE.md §7)
// [수정 시 고려사항] 알림 공시 종류 토글은 현재 UI 전용 — BE notification_types 미구현(후속 스펙).
//   평가 손익은 시세 API 연동 후 실시간 계산 예정(현재 "—" placeholder).
//   code 없이 직접 접근 시 /portfolios/new 로 리디렉트.

import { useState, useEffect, Suspense } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useForm, Controller } from "react-hook-form";
import Link from "next/link";
import { ArrowLeft, FileText, BarChart2, Users, ShieldCheck, ChevronUp, ChevronDown } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { useCreatePortfolio } from "@/lib/api/portfolios";
import { ApiException } from "@/lib/api/client";
import { API_ERROR_CODES } from "@/lib/api/errorCodes";

interface FormValues {
  avg_buy_price: string;
  quantity: string;
}

const PRICE_PRESETS = [
  { label: "5천",   value: 5_000 },
  { label: "1만",   value: 10_000 },
  { label: "5만",   value: 50_000 },
  { label: "10만",  value: 100_000 },
  { label: "50만",  value: 500_000 },
  { label: "100만", value: 1_000_000 },
  { label: "500만", value: 5_000_000 },
  { label: "1000만", value: 10_000_000 },
] as const;

const DISCLOSURE_TYPES = [
  { key: "major_report", label: "주요사항보고서", desc: "증자·CB·합병 등",    Icon: FileText,  defaultOn: true  },
  { key: "earnings",     label: "실적·배당",      desc: "분기 실적, 배당 공시", Icon: BarChart2, defaultOn: true  },
  { key: "ownership",    label: "지분·임원 변동", desc: "대주주·임원 매매",    Icon: Users,     defaultOn: false },
] as const;

function AddPortfolioForm() {
  const router = useRouter();
  const params = useSearchParams();
  const stockCode  = params.get("code") ?? "";
  const stockName  = decodeURIComponent(params.get("name") ?? "");
  const stockMarket = params.get("market") ?? "";

  const { mutateAsync, isPending } = useCreatePortfolio();

  const [notifTypes, setNotifTypes] = useState<Record<string, boolean>>(
    () => Object.fromEntries(DISCLOSURE_TYPES.map((t) => [t.key, t.defaultOn]))
  );

  useEffect(() => {
    if (!stockCode) router.replace("/portfolios/new");
  // stockCode는 마운트 시 한 번만 평가
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { register, handleSubmit, setError, setValue, getValues, control, formState: { errors } } = useForm<FormValues>({
    defaultValues: { avg_buy_price: "", quantity: "" },
  });

  const handlePriceStep = (direction: "up" | "down") => {
    const current = Number(getValues("avg_buy_price")) || 0;
    if (direction === "up") {
      setValue("avg_buy_price", String(current + 10000), { shouldValidate: true });
    } else {
      if (current <= 10000) return;
      setValue("avg_buy_price", String(current - 10000), { shouldValidate: true });
    }
  };

  const handleQuantityStep = (direction: "up" | "down") => {
    const current = Number(getValues("quantity")) || 0;
    if (direction === "up") {
      setValue("quantity", String(current + 1), { shouldValidate: true });
    } else {
      if (current <= 1) return;
      setValue("quantity", String(current - 1), { shouldValidate: true });
    }
  };

  const onSubmit = async (data: FormValues) => {
    if (!stockCode) { router.push("/portfolios/new"); return; }

    const body = {
      stock_code: stockCode,
      avg_buy_price: data.avg_buy_price ? Number(data.avg_buy_price) : undefined,
      quantity: data.quantity ? Number(data.quantity) : undefined,
    };

    // avg_buy_price·quantity는 절대 console.log 금지 — 금융 개인정보

    try {
      await mutateAsync(body);
      router.push("/portfolios");
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

  const marketLabel = stockMarket === "KOSPI" ? "코스피" : stockMarket === "KOSDAQ" ? "코스닥" : "";

  return (
    <div className="flex flex-col gap-6">
      {/* 뒤로가기 */}
      <Link
        href="/portfolios/new"
        className="inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
      >
        <ArrowLeft className="size-4" aria-hidden />
        종목 목록으로
      </Link>

      {/* 종목 헤더 */}
      <div className="flex items-center gap-4">
        <div
          className="grid size-14 shrink-0 place-items-center rounded-2xl bg-primary font-extrabold text-base text-primary-foreground"
          aria-hidden
        >
          {stockName.slice(0, 2)}
        </div>
        <div>
          <h1 className="text-xl font-extrabold text-foreground">{stockName || "종목 선택 필요"}</h1>
          <p className="font-mono text-sm text-muted-foreground">
            {stockCode}{marketLabel ? ` · ${marketLabel}` : ""}
          </p>
        </div>
      </div>

      {/* 2열 폼 */}
      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <div className="grid gap-5 lg:grid-cols-2 lg:items-start">
          {/* 좌측: 보유 정보 */}
          <div className="flex flex-col gap-5 rounded-2xl border border-border bg-card p-5 shadow-sm">
            <p className="text-sm font-extrabold text-foreground">
              보유 정보{" "}
              <span className="text-xs font-normal text-muted-foreground">(선택·손익 분석에 사용)</span>
            </p>

            {errors.root && (
              <p className="rounded-lg bg-destructive/10 px-4 py-2.5 text-sm text-destructive" role="alert">
                {errors.root.message}
                {errors.root.message?.includes("Pro") && (
                  <> <Link href="/pricing" className="font-bold underline">요금제 보기</Link></>
                )}
              </p>
            )}

            {/* 평균 매수가 + 보유 수량 (2열) */}
            <div className="grid grid-cols-2 gap-4">
              <div className="flex flex-col gap-1.5">
                <label htmlFor="avg_buy_price" className="text-xs font-semibold text-muted-foreground">
                  평균 매수가
                </label>
                <Controller
                  control={control}
                  name="avg_buy_price"
                  rules={{ min: { value: 1, message: "1원 이상 입력해주세요" } }}
                  render={({ field: { onChange, value, ref } }) => (
                    <div className={`flex items-center overflow-hidden rounded-xl border bg-background focus-within:ring-2 focus-within:ring-primary/20 ${errors.avg_buy_price ? "border-destructive" : "border-border focus-within:border-primary"}`}>
                      <input
                        id="avg_buy_price"
                        ref={ref}
                        type="text"
                        inputMode="numeric"
                        placeholder="168,000"
                        autoComplete="off"
                        className="min-w-0 flex-1 bg-transparent px-3.5 py-3 text-sm text-foreground tabular-nums placeholder:text-muted-foreground focus:outline-none"
                        value={value ? Number(value).toLocaleString("ko-KR") : ""}
                        onChange={(e) => {
                          const raw = e.target.value.replace(/[^0-9]/g, "");
                          onChange(raw);
                        }}
                        onKeyDown={(e) => {
                          if (e.key === "ArrowUp")   { e.preventDefault(); handlePriceStep("up");   }
                          if (e.key === "ArrowDown") { e.preventDefault(); handlePriceStep("down"); }
                        }}
                      />
                      <span className="shrink-0 px-2 text-xs text-muted-foreground">원</span>
                      <div className="flex shrink-0 flex-col pr-1">
                        <button
                          type="button"
                          tabIndex={-1}
                          onMouseDown={(e) => e.preventDefault()}
                          onClick={() => handlePriceStep("up")}
                          className="flex items-center justify-center rounded px-1 py-1.5 text-muted-foreground transition-colors hover:text-foreground"
                          aria-label="10,000원 증가"
                        >
                          <ChevronUp className="size-3" />
                        </button>
                        <button
                          type="button"
                          tabIndex={-1}
                          onMouseDown={(e) => e.preventDefault()}
                          onClick={() => handlePriceStep("down")}
                          className="flex items-center justify-center rounded px-1 py-1.5 text-muted-foreground transition-colors hover:text-foreground"
                          aria-label="10,000원 감소"
                        >
                          <ChevronDown className="size-3" />
                        </button>
                      </div>
                    </div>
                  )}
                />
                {errors.avg_buy_price && (
                  <p className="text-xs text-destructive" role="alert">{errors.avg_buy_price.message}</p>
                )}
              </div>

              <div className="flex flex-col gap-1.5">
                <label htmlFor="quantity" className="text-xs font-semibold text-muted-foreground">
                  보유 수량
                </label>
                <div className={`flex items-center overflow-hidden rounded-xl border bg-background focus-within:ring-2 focus-within:ring-primary/20 ${errors.quantity ? "border-destructive" : "border-border focus-within:border-primary"}`}>
                  <input
                    id="quantity"
                    type="number"
                    inputMode="numeric"
                    min="1"
                    step="any"
                    placeholder="25"
                    autoComplete="off"
                    className="min-w-0 flex-1 bg-transparent px-3.5 py-3 text-sm text-foreground tabular-nums placeholder:text-muted-foreground focus:outline-none [&::-webkit-inner-spin-button]:appearance-none [&::-webkit-outer-spin-button]:appearance-none"
                    onKeyDown={(e) => {
                      if (e.key === "ArrowUp")   { e.preventDefault(); handleQuantityStep("up");   }
                      if (e.key === "ArrowDown") { e.preventDefault(); handleQuantityStep("down"); }
                    }}
                    {...register("quantity", {
                      min: { value: 1, message: "1 이상" },
                      validate: (v) => !v || Number.isInteger(Number(v)) || "정수만 입력",
                    })}
                  />
                  <span className="shrink-0 px-2 text-xs text-muted-foreground">주</span>
                  <div className="flex shrink-0 flex-col pr-1">
                    <button
                      type="button"
                      tabIndex={-1}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => handleQuantityStep("up")}
                      className="flex items-center justify-center rounded px-1 py-1.5 text-muted-foreground transition-colors hover:text-foreground"
                      aria-label="1주 증가"
                    >
                      <ChevronUp className="size-3" />
                    </button>
                    <button
                      type="button"
                      tabIndex={-1}
                      onMouseDown={(e) => e.preventDefault()}
                      onClick={() => handleQuantityStep("down")}
                      className="flex items-center justify-center rounded px-1 py-1.5 text-muted-foreground transition-colors hover:text-foreground"
                      aria-label="1주 감소"
                    >
                      <ChevronDown className="size-3" />
                    </button>
                  </div>
                </div>
                {errors.quantity && (
                  <p className="text-xs text-destructive" role="alert">{errors.quantity.message}</p>
                )}
              </div>
            </div>

            {/* 빠른 금액 선택 */}
            <div className="grid grid-cols-4 gap-1.5 sm:grid-cols-8">
              {PRICE_PRESETS.map(({ label, value }) => (
                <button
                  key={value}
                  type="button"
                  onMouseDown={(e) => e.preventDefault()}
                  onClick={() => {
                    const current = Number(getValues("avg_buy_price")) || 0;
                    setValue("avg_buy_price", String(current + value), { shouldValidate: true });
                  }}
                  className="rounded-lg border border-border bg-background py-1.5 text-xs font-semibold text-muted-foreground transition-colors hover:border-primary hover:text-primary"
                >
                  {label}
                </button>
              ))}
            </div>

            {/* AES-256 보안 안내 */}
            <div className="flex items-start gap-2.5 rounded-xl border border-border bg-background px-4 py-3.5 text-sm text-muted-foreground">
              <ShieldCheck className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
              <span>
                매수가·수량은{" "}
                <strong className="font-semibold text-foreground">AES-256 암호화</strong>되어 저장되며,
                손익 영향 분석에만 쓰입니다.
              </span>
            </div>
          </div>

          {/* 우측: 알림 공시 종류 + 저장 */}
          <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm">
            <p className="text-sm font-extrabold text-foreground">알림 받을 공시 종류</p>

            <div className="flex flex-col divide-y divide-border">
              {DISCLOSURE_TYPES.map(({ key, label, desc, Icon }) => (
                <div key={key} className="flex items-center justify-between gap-3 py-3.5 first:pt-0 last:pb-0">
                  <div className="flex items-center gap-3">
                    <div className="grid size-9 shrink-0 place-items-center rounded-xl border border-border bg-background text-muted-foreground">
                      <Icon className="size-4" aria-hidden />
                    </div>
                    <div>
                      <p className="text-sm font-semibold text-foreground">{label}</p>
                      <p className="text-xs text-muted-foreground">{desc}</p>
                    </div>
                  </div>
                  <Switch
                    checked={notifTypes[key]}
                    onCheckedChange={(checked) =>
                      setNotifTypes((prev) => ({ ...prev, [key]: checked }))
                    }
                    aria-label={`${label} 알림`}
                  />
                </div>
              ))}
            </div>

            {/* 저장하기 */}
            <Button type="submit" disabled={isPending || !stockCode} className="w-full">
              {isPending ? "저장 중..." : "저장하기"}
            </Button>
          </div>
        </div>
      </form>
    </div>
  );
}

export default function AddPortfolioPage() {
  return (
    <Suspense>
      <AddPortfolioForm />
    </Suspense>
  );
}
