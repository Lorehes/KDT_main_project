"use client";

// [목적] 보유 종목 등록 Sheet — 2-step(검색→정보입력) 인라인 Sheet, 페이지 이탈 없이 POST /portfolios 완료
// [이유] 온보딩(signup/complete)·알림센터(notifications) 공통 재사용을 위해 shared component로 분리
// [사이드 임팩트] mutateAsync 성공 시 useCreatePortfolio 내부에서 ["portfolios"] 쿼리 무효화.
//   avg_buy_price·quantity 절대 console.log 금지(CLAUDE.md §7 금융 개인정보).
//   useId()로 동적 id 생성 — 동일 페이지에 여러 인스턴스가 마운트되어도 WCAG §4.1.1 중복 id 없음.
// [수정 시 고려사항] Free 3종목 422·중복 409 에러 분기는 portfolios/new/page.tsx 패턴과 동일하게 유지.
//   Sheet 닫힘(onOpenChange false) 시 selectedStock·form 상태 자동 초기화 → 다음 오픈 시 Step1 시작.
//   contentClassName으로 호출부에서 높이·패딩 오버라이드 가능 (예: BOTTOM_SHEET_MIN_HEIGHT from useSheetSide).
//   avg_buy_price max 999,999,999·quantity max 100,000,000 — BE @DecimalMax 값과 정합 유지.

import Link from "next/link";
import { useId, useState } from "react";
import { ChevronLeft } from "lucide-react";
import { useForm } from "react-hook-form";
import { Button } from "@/components/ui/button";
import { Sheet, SheetContent, SheetHeader, SheetTitle } from "@/components/ui/sheet";
import { StockSearchCombobox } from "@/components/domain/StockSearchCombobox";
import { useCreatePortfolio } from "@/lib/api/portfolios";
import { ApiException } from "@/lib/api/client";
import { API_ERROR_CODES } from "@/lib/api/errorCodes";
import { cn } from "@/lib/utils";
import type { StockSearchResult } from "@/lib/api/stocks";

export interface PortfolioSheetProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  side: "bottom" | "right";
  onSuccess?: () => void;
  contentClassName?: string;
}

interface PortfolioFormValues {
  avg_buy_price: string;
  quantity: string;
}

export function PortfolioSheet({ open, onOpenChange, side, onSuccess, contentClassName }: PortfolioSheetProps) {
  const uid = useId();
  const ids = {
    avgPrice:     `${uid}-avg-price`,
    quantity:     `${uid}-quantity`,
    priceHint:    `${uid}-price-hint`,
    quantityHint: `${uid}-quantity-hint`,
  };

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
      onSuccess?.();
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
      <SheetContent side={side} className={cn("flex flex-col overflow-y-auto", contentClassName)}>
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
                <label htmlFor={ids.avgPrice} className="text-sm font-semibold text-foreground">
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
                    id={ids.avgPrice}
                    type="number"
                    inputMode="decimal"
                    min="1"
                    step="1"
                    placeholder="예: 75000"
                    autoComplete="off"
                    aria-required="true"
                    aria-describedby={ids.priceHint}
                    className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                    {...register("avg_buy_price", {
                      required: "매수 평균가를 입력해주세요",
                      min: { value: 1, message: "1 이상이어야 합니다" },
                      max: { value: 999_999_999, message: "범위를 초과했습니다" },
                    })}
                  />
                  <span className="shrink-0 text-sm text-muted-foreground">원</span>
                </div>
                {errors.avg_buy_price && (
                  <p className="text-xs text-destructive" role="alert">{errors.avg_buy_price.message}</p>
                )}
                <p id={ids.priceHint} className="text-xs text-muted-foreground">손익 계산에 사용됩니다.</p>
              </div>

              {/* 보유 수량 */}
              <div className="flex flex-col gap-1.5">
                <label htmlFor={ids.quantity} className="text-sm font-semibold text-foreground">
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
                    id={ids.quantity}
                    type="number"
                    inputMode="numeric"
                    min="1"
                    step="1"
                    placeholder="예: 10"
                    autoComplete="off"
                    aria-required="true"
                    aria-describedby={ids.quantityHint}
                    className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                    {...register("quantity", {
                      required: "보유 수량을 입력해주세요",
                      min: { value: 1, message: "1 이상이어야 합니다" },
                      max: { value: 100_000_000, message: "범위를 초과했습니다" },
                      validate: (v) => Number.isInteger(Number(v)) || "정수를 입력해주세요",
                    })}
                  />
                  <span className="shrink-0 text-sm text-muted-foreground">주</span>
                </div>
                {errors.quantity && (
                  <p className="text-xs text-destructive" role="alert">{errors.quantity.message}</p>
                )}
                <p id={ids.quantityHint} className="text-xs text-muted-foreground">주(株) 단위 정수로 입력해주세요.</p>
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
