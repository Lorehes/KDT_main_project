"use client";

// [목적] 종목 등록 상세 화면(D13/m22) — 매수 평균가·수량 입력 + 저장
// [이유] 선택 종목의 매수 정보를 입력해 손익 계산 기반을 마련. 매수가·수량은 필수 입력(손익 계산 필요)
// [사이드 임팩트] POST /portfolios 호출 후 ["portfolios"] 쿼리 무효화 → 목록 자동 갱신.
//   매수가·수량은 평문 console.log 절대 금지 — 백엔드에서 AES-256-GCM 암호화 저장(CLAUDE.md §7)
// [수정 시 고려사항] edit=true 모드(기존 종목 수정)는 PATCH /portfolios/{id} 사용 — 현재 미구현.
//   Free 3종목 초과 시 422 에러 인라인 표시. 동일 종목 중복 등록 시 409 인라인 표시.
//   알림 on/off는 계정 전역 설정(/notifications/settings)으로 일원화 — per-stock 토글 MVP 제외(R3 옵션 A).

import { useSearchParams, useRouter } from "next/navigation";
import { Suspense, useEffect } from "react";
import { useForm } from "react-hook-form";
import Link from "next/link";
import { ArrowLeft, Bell } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useCreatePortfolio } from "@/lib/api/portfolios";
import { ApiException } from "@/lib/api/client";
import { API_ERROR_CODES } from "@/lib/api/errorCodes";
import { cn } from "@/lib/utils";

interface FormValues {
  avg_buy_price: string;
  quantity: string;
}

function NewPortfolioForm() {
  const router = useRouter();
  const params = useSearchParams();
  const stockCode = params.get("code") ?? "";
  const stockName = decodeURIComponent(params.get("name") ?? "");

  const { mutateAsync, isPending } = useCreatePortfolio();

  // code 없이 직접 접근하면 종목 목록(검색)으로 redirect
  useEffect(() => {
    if (!stockCode) router.replace("/portfolios");
  // stockCode는 마운트 시 한 번만 평가
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { register, handleSubmit, setError, formState: { errors } } = useForm<FormValues>({
    defaultValues: { avg_buy_price: "", quantity: "" },
  });

  const onSubmit = async (data: FormValues) => {
    if (!stockCode) { router.push("/portfolios"); return; }

    const body = {
      stock_code: stockCode,
      avg_buy_price: Number(data.avg_buy_price),
      quantity: Number(data.quantity),
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
      }
    }
  };

  return (
    <div className="mx-auto max-w-lg">
      <Link href="/portfolios" className="mb-6 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
        <ArrowLeft className="size-4" aria-hidden />
        종목 목록
      </Link>

      <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
        {/* 종목 정보 헤더 */}
        <div className="mb-6 flex items-center gap-3">
          <div className="grid size-11 shrink-0 place-items-center rounded-xl bg-primary font-extrabold text-sm text-primary-foreground" aria-hidden>
            {stockName.slice(0, 2)}
          </div>
          <div>
            <h1 className="text-lg font-extrabold text-foreground">{stockName || "종목 선택 필요"}</h1>
            <p className="font-mono text-sm text-muted-foreground">{stockCode}</p>
          </div>
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-5" noValidate>
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
            <label htmlFor="avg_buy_price" className="text-sm font-semibold text-foreground">
              매수 평균가 <span className="text-destructive" aria-hidden>*</span>
            </label>
            <div className={`flex items-center gap-2 rounded-xl border bg-background px-4 py-3 focus-within:ring-2 focus-within:ring-primary/20 ${errors.avg_buy_price ? "border-destructive focus-within:border-destructive" : "border-border focus-within:border-primary"}`}>
              <input
                id="avg_buy_price"
                type="number"
                inputMode="decimal"
                min="1"
                step="1"
                placeholder="예: 75000"
                autoComplete="off"
                aria-required="true"
                aria-describedby="price-hint"
                className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                {...register("avg_buy_price", {
                  required: "매수 평균가를 입력해주세요",
                  min: { value: 1, message: "1 이상이어야 합니다" },
                })}
              />
              <span className="shrink-0 text-sm text-muted-foreground">원</span>
            </div>
            {errors.avg_buy_price && <p className="text-xs text-destructive" role="alert">{errors.avg_buy_price.message}</p>}
            <p id="price-hint" className="text-xs text-muted-foreground">손익 계산에 사용됩니다.</p>
          </div>

          {/* 보유 수량 */}
          <div className="flex flex-col gap-1.5">
            <label htmlFor="quantity" className="text-sm font-semibold text-foreground">
              보유 수량 <span className="text-destructive" aria-hidden>*</span>
            </label>
            <div className={`flex items-center gap-2 rounded-xl border bg-background px-4 py-3 focus-within:ring-2 focus-within:ring-primary/20 ${errors.quantity ? "border-destructive focus-within:border-destructive" : "border-border focus-within:border-primary"}`}>
              <input
                id="quantity"
                type="number"
                inputMode="numeric"
                min="1"
                step="1"
                placeholder="예: 10"
                autoComplete="off"
                aria-required="true"
                className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                {...register("quantity", {
                  required: "보유 수량을 입력해주세요",
                  min: { value: 1, message: "1 이상이어야 합니다" },
                  validate: (v) => Number.isInteger(Number(v)) || "정수를 입력해주세요",
                })}
              />
              <span className="shrink-0 text-sm text-muted-foreground">주</span>
            </div>
            {errors.quantity && <p className="text-xs text-destructive" role="alert">{errors.quantity.message}</p>}
          </div>

          {/* 알림 설정 안내 */}
          <div className="flex items-center gap-2.5 rounded-xl border border-border bg-background px-4 py-3.5 text-sm text-muted-foreground">
            <Bell className="size-4 shrink-0" aria-hidden />
            <span>
              공시 알림은{" "}
              <Link
                href="/notifications/settings"
                className="font-semibold text-foreground underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                알림 설정
              </Link>
              에서 종목별로 관리할 수 있습니다.
            </span>
          </div>

          <div className="flex gap-3 pt-1">
            <Link href="/portfolios" className={cn("flex-1", "inline-flex h-9 items-center justify-center rounded-lg border border-border bg-background px-4 text-sm font-bold text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring")}>
              취소
            </Link>
            <Button type="submit" disabled={isPending || !stockCode} className="flex-1">
              {isPending ? "저장 중..." : "저장"}
            </Button>
          </div>
        </form>
      </div>
    </div>
  );
}

export default function NewPortfolioPage() {
  return (
    <Suspense>
      <NewPortfolioForm />
    </Suspense>
  );
}
