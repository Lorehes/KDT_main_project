"use client";

// [목적] 종목 관리 페이지(D12/m05·D12b/m05b) — 보유 종목 목록 + 검색 자동완성 + Free 쿼터 표시
// [이유] 사용자가 등록한 종목을 한눈에 확인하고 추가·삭제하는 핵심 관리 화면
// [사이드 임팩트] usePortfolios·useDeletePortfolio 쿼리. 삭제 후 ["portfolios"] 자동 무효화
//   StockSearchCombobox → /portfolios/new?code= 로 이동 (선택 즉시 등록 상세 페이지)
// [수정 시 고려사항] Free 3종목 초과 시 422 BUSINESS_RULE_VIOLATION — UI에서 검색 비활성화.
//   매수가·수량은 목록에 표시하되 console.log 절대 금지(금융 개인정보, CLAUDE.md §7)

import { useRouter } from "next/navigation";
import Link from "next/link";
import { Trash2, Bell } from "lucide-react";
import { usePortfolios, useDeletePortfolio } from "@/lib/api/portfolios";
import { useAuthStore } from "@/lib/stores/authStore";
import { StockSearchCombobox } from "@/components/domain/StockSearchCombobox";
import { Button, buttonVariants } from "@/components/ui/button";
import type { StockSearchResult } from "@/lib/api/stocks";

const FREE_LIMIT = 3;

export default function PortfoliosPage() {
  const router = useRouter();
  const { user } = useAuthStore();
  const { data: portfolios, isLoading } = usePortfolios();
  const { mutate: deletePortfolio, isPending: isDeleting } = useDeletePortfolio();

  const isPro = user?.tier === "PRO" || user?.tier === "PREMIUM";
  const count = portfolios?.length ?? 0;
  const atLimit = !isPro && count >= FREE_LIMIT;

  const handleSelect = (stock: StockSearchResult) => {
    router.push(`/portfolios/new?code=${stock.stock_code}&name=${encodeURIComponent(stock.corp_name)}`);
  };

  const handleDelete = (id: number, name: string) => {
    if (!confirm(`"${name}"을 삭제하시겠습니까?`)) return;
    deletePortfolio(id);
  };

  return (
    <div className="flex flex-col gap-6">
      {/* 헤더 */}
      <div className="flex items-center justify-between">
        <div>
          <p className="text-xs font-extrabold uppercase tracking-widest text-primary">My Portfolio</p>
          <h1 className="mt-1 text-2xl font-extrabold tracking-tight text-foreground">보유 종목 등록</h1>
          <p className="mt-1 text-sm text-muted-foreground">관심 종목을 추가하면 해당 공시가 도착할 때 알려드려요.</p>
        </div>
        <Link href="/portfolios/new" className={buttonVariants({ size: "sm" })} aria-label="종목 추가">
          ＋ 추가
        </Link>
      </div>

      <div className="grid gap-5 lg:grid-cols-[1.3fr_1fr] lg:items-start">
        {/* 등록 목록 + 검색 */}
        <div className="flex flex-col gap-4">
          {/* 검색 */}
          <div className="flex flex-col gap-2">
            <StockSearchCombobox
              onSelect={handleSelect}
              placeholder={atLimit ? "Free 플랜 3종목 초과 — Pro 업그레이드 후 추가" : "종목명 또는 코드 검색"}
            />
            {atLimit && (
              <p className="text-xs text-muted-foreground">
                Free 플랜은 최대 3종목까지 등록 가능합니다.{" "}
                <Link href="/pricing" className="font-bold text-primary hover:underline">Pro로 무제한 등록 →</Link>
              </p>
            )}
          </div>

          {/* Free 쿼터 바 */}
          {!isPro && (
            <div>
              <div className="mb-1.5 flex items-center justify-between text-xs">
                <span className="font-semibold text-muted-foreground">Free 쿼터</span>
                <span className={`font-extrabold ${atLimit ? "text-destructive" : "text-foreground"}`}>
                  {count} / {FREE_LIMIT}
                </span>
              </div>
              <div className="h-2 overflow-hidden rounded-full bg-muted" role="progressbar" aria-valuenow={count} aria-valuemin={0} aria-valuemax={FREE_LIMIT} aria-label={`Free 종목 쿼터 ${count}/${FREE_LIMIT}`}>
                <div
                  className={`h-full rounded-full transition-all ${atLimit ? "bg-destructive" : "bg-primary"}`}
                  style={{ width: `${(count / FREE_LIMIT) * 100}%` }}
                />
              </div>
            </div>
          )}

          {/* 등록 종목 리스트 */}
          <div className="rounded-2xl border border-border bg-card shadow-sm">
            {isLoading ? (
              <div className="py-10 text-center text-sm text-muted-foreground" role="status">불러오는 중...</div>
            ) : !portfolios?.length ? (
              <div className="py-10 text-center text-sm text-muted-foreground">
                아직 등록된 종목이 없습니다.
              </div>
            ) : (
              <ul aria-label="등록 종목 목록" className="divide-y divide-border">
                {portfolios.map((p) => (
                  <li key={p.id} className="flex items-center justify-between px-5 py-4">
                    <div className="flex items-center gap-3">
                      <div className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground" aria-hidden>
                        {(p.corp_name ?? p.stock_code).slice(0, 2)}
                      </div>
                      <div>
                        <p className="text-sm font-bold text-foreground">{p.corp_name ?? p.stock_code}</p>
                        <p className="font-mono text-xs text-muted-foreground">{p.stock_code}</p>
                      </div>
                    </div>

                    <div className="flex items-center gap-2">
                      {/* 알림 아이콘 — BE notify_enabled 미지원(PortfolioResponse에 없음). 알림 설정은 계정 전역 설정으로 관리 */}
                      <Bell className="size-4 text-primary" aria-label="알림 켜짐" />
                      <Link
                        href={`/portfolios/new?code=${p.stock_code}&name=${encodeURIComponent(p.corp_name ?? p.stock_code)}&edit=${p.id}`}
                        className={buttonVariants({ variant: "ghost", size: "sm" })}
                        aria-label={`${p.corp_name ?? p.stock_code} 수정`}
                      >
                        수정
                      </Link>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => handleDelete(p.id, p.corp_name ?? p.stock_code)}
                        disabled={isDeleting}
                        className="text-destructive hover:bg-destructive/10 hover:text-destructive"
                        aria-label={`${p.corp_name ?? p.stock_code} 삭제`}
                      >
                        <Trash2 className="size-4" aria-hidden />
                      </Button>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </div>
        </div>

        {/* 추천 종목 사이드 */}
        <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
          <p className="mb-4 text-sm font-extrabold text-foreground">이런 종목은 어때요?</p>
          <ul className="flex flex-col gap-3">
            {/* 추천 종목 아이콘 배경은 bg-primary 단일 색으로 통일 (P2 토큰화 — CLAUDE.md §6-4) */}
            {[
              { code: "005930", name: "삼성전자",  abbr: "SE", market: "코스피" },
              { code: "035420", name: "NAVER",     abbr: "NV", market: "코스피" },
              { code: "000660", name: "SK하이닉스", abbr: "SK", market: "코스피" },
              { code: "035720", name: "카카오",     abbr: "KK", market: "코스피" },
            ].map(({ code, name, abbr, market }) => (
              <li key={code} className="flex items-center justify-between rounded-xl border border-border bg-background px-3.5 py-2.5">
                <div className="flex items-center gap-2.5">
                  <div className="grid size-8 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground" aria-hidden>{abbr}</div>
                  <div>
                    <p className="text-sm font-bold text-foreground">{name}</p>
                    <p className="font-mono text-xs text-muted-foreground">{code} · {market}</p>
                  </div>
                </div>
                <button
                  type="button"
                  onClick={() => handleSelect({ stock_code: code, corp_name: name, market: market as "KOSPI" | "KOSDAQ" })}
                  disabled={atLimit}
                  className={buttonVariants({ variant: "outline", size: "sm" }) + " disabled:opacity-40"}
                  aria-label={`${name} 추가`}
                >
                  ＋ 추가
                </button>
              </li>
            ))}
          </ul>
        </div>
      </div>
    </div>
  );
}
