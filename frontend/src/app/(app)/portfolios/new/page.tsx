"use client";

// [목적] 종목 관리 페이지(D12/m05·D12b/m05b) — 좌: 검색+CSV업로드 / 우: 등록된 종목+알림 CTA
// [이유] 검색과 등록 목록을 패널로 분리해 종목 추가 흐름을 명확히 함 (이전: 단일 패널)
// [사이드 임팩트] usePortfolios·useDeletePortfolio 쿼리. 삭제 후 ["portfolios"] 자동 무효화.
//   useStockSearch 직접 사용(실시간 드롭다운) — StockSearchCombobox 제거.
// [수정 시 고려사항] Free 3종목 초과 시 422 BUSINESS_RULE_VIOLATION — atLimit에 isLoading 포함.
//   매수가·수량은 console.log 절대 금지(금융 개인정보, CLAUDE.md §7).
//   종목 선택 후 이동 경로: /portfolios/add?code=...&name=... (기존 /portfolios/new → /portfolios/add 로 변경)

import { useCallback, useState, useRef, useEffect } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { Search, Upload, X, CircleHelp } from "lucide-react";
import { usePortfolios, useDeletePortfolio } from "@/lib/api/portfolios";
import { useStockSearch } from "@/lib/api/stocks";
import { useTierCheck } from "@/lib/hooks/useTierCheck";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { Skeleton } from "@/components/ui/skeleton";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle,
  AlertDialogDescription, AlertDialogFooter, AlertDialogAction, AlertDialogCancel,
} from "@/components/ui/alert-dialog";
import type { StockSearchResult } from "@/lib/api/stocks";

const FREE_LIMIT = 3;

function useDebounce(value: string, delay = 300) {
  const [debounced, setDebounced] = useState(value);
  useEffect(() => {
    const id = setTimeout(() => setDebounced(value), delay);
    return () => clearTimeout(id);
  }, [value, delay]);
  return debounced;
}

export default function PortfoliosNewPage() {
  const router = useRouter();
  const { isPro } = useTierCheck();
  const { data: portfolios, isLoading } = usePortfolios();
  const { mutate: deletePortfolio, isPending: isDeleting } = useDeletePortfolio();
  const showSkeleton = useDelayedLoading(isLoading);
  const [deleteTarget, setDeleteTarget] = useState<{ id: number; name: string } | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const [searchInput, setSearchInput] = useState("");
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const searchContainerRef = useRef<HTMLDivElement>(null);
  const debouncedQuery = useDebounce(searchInput);
  const { data: searchResults, isLoading: isSearching } = useStockSearch(debouncedQuery);

  const count = portfolios?.length ?? 0;
  const atLimit = isLoading || (!isPro && count >= FREE_LIMIT);

  const handleSelect = (stock: StockSearchResult) => {
    setSearchInput("");
    setDropdownOpen(false);
    router.push(`/portfolios/add?code=${stock.stock_code}&name=${encodeURIComponent(stock.corp_name)}&market=${stock.market}`);
  };

  const handleDelete = useCallback((id: number, name: string) => {
    setDeleteTarget({ id, name });
  }, []);

  const confirmDelete = useCallback(() => {
    if (!deleteTarget) return;
    setDeleteError(null);
    deletePortfolio(deleteTarget.id, {
      onSuccess: () => setDeleteTarget(null),
      onError: () => setDeleteError("삭제에 실패했습니다. 잠시 후 다시 시도해주세요."),
    });
  }, [deleteTarget, deletePortfolio]);

  return (
    <div className="flex flex-col gap-6">
      {/* 헤더 */}
      <div>
        <p className="text-xs font-extrabold uppercase tracking-widest text-primary">My Portfolio</p>
        <h1 className="mt-1 text-2xl font-extrabold tracking-tight text-foreground">보유 종목 등록</h1>
        <p className="mt-1 text-sm text-muted-foreground">관심 종목을 추가하면 해당 공시가 도착할 때 알려드려요.</p>
      </div>

      <div className="grid gap-5 lg:grid-cols-[1.3fr_1fr] lg:items-start">
        {/* 좌측: 검색 + 결과 + CSV 업로드 */}
        <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm">
          {/* 검색 입력 + 실시간 드롭다운 */}
          <div className="relative" ref={searchContainerRef}>
            <div className="flex gap-2">
              <div className="flex flex-1 items-center gap-2 rounded-xl border border-border bg-background px-3.5 py-2.5 focus-within:border-primary focus-within:ring-2 focus-within:ring-primary/20">
                <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
                <input
                  type="search"
                  value={searchInput}
                  onChange={(e) => { setSearchInput(e.target.value); setDropdownOpen(e.target.value.trim().length > 0); }}
                  onFocus={() => { if (searchInput.trim()) setDropdownOpen(true); }}
                  onBlur={(e) => { if (!searchContainerRef.current?.contains(e.relatedTarget as Node)) setDropdownOpen(false); }}
                  onKeyDown={(e) => { if (e.key === "Escape") { setDropdownOpen(false); } }}
                  placeholder={atLimit ? "Free 플랜 3종목 초과 — Pro 업그레이드 후 추가" : "종목명 또는 코드 검색"}
                  className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                  aria-label="종목 검색"
                  aria-expanded={dropdownOpen}
                  aria-autocomplete="list"
                  aria-controls="stock-dropdown"
                  aria-haspopup="listbox"
                  role="combobox"
                />
              </div>
              <Button className="shrink-0" onClick={() => { if (searchInput.trim()) setDropdownOpen(true); }}>검색</Button>
            </div>

            {/* 드롭다운 */}
            {dropdownOpen && searchInput.trim() && (
              <ul
                id="stock-dropdown"
                role="listbox"
                aria-label="종목 검색 결과"
                className="absolute left-0 right-0 top-full z-50 mt-1 overflow-hidden rounded-xl border border-border bg-background shadow-md"
              >
                {isSearching && (
                  <li className="px-4 py-3 text-sm text-muted-foreground" role="status" aria-live="polite">검색 중...</li>
                )}
                {!isSearching && searchResults?.length === 0 && (
                  <li className="px-4 py-3 text-sm text-muted-foreground">검색 결과가 없습니다.</li>
                )}
                {!isSearching && searchResults?.map((stock) => (
                  <li key={stock.stock_code} role="option" aria-selected={false}>
                    <div className="flex items-center justify-between px-4 py-3 hover:bg-muted">
                      <div className="flex items-center gap-2.5">
                        <div className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground" aria-hidden>
                          {stock.corp_name.slice(0, 2)}
                        </div>
                        <div>
                          <p className="text-sm font-bold text-foreground">{stock.corp_name}</p>
                          <p className="font-mono text-xs text-muted-foreground">{stock.stock_code} · {stock.market}</p>
                        </div>
                      </div>
                      <button
                        type="button"
                        onMouseDown={(e) => e.preventDefault()}
                        onClick={() => handleSelect(stock)}
                        disabled={atLimit}
                        className={buttonVariants({ variant: "outline", size: "sm" }) + " disabled:opacity-40"}
                        aria-label={`${stock.corp_name} 추가`}
                      >
                        + 추가
                      </button>
                    </div>
                  </li>
                ))}
                {atLimit && !isSearching && (searchResults?.length ?? 0) > 0 && (
                  <li className="border-t border-border px-4 py-2.5">
                    <p className="text-xs text-muted-foreground">
                      Free 플랜은 최대 3종목까지 등록 가능합니다.{" "}
                      <Link href="/pricing" className="font-bold text-primary hover:underline">Pro로 무제한 등록 →</Link>
                    </p>
                  </li>
                )}
              </ul>
            )}
          </div>

          {/* CSV 업로드 */}
          <div className="flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed border-border bg-background py-8 text-center">
            <div className="grid size-10 place-items-center rounded-xl border border-border bg-card">
              <Upload className="size-5 text-muted-foreground" aria-hidden />
            </div>
            <p className="text-sm font-semibold text-foreground">증권사 거래내역 CSV 업로드</p>
            <p className="text-xs text-muted-foreground">파일을 끌어다 놓으면 보유 종목을 자동 추출</p>
          </div>
        </div>

        {/* 우측: 등록된 종목 */}
        <div className="flex flex-col gap-4 rounded-2xl border border-border bg-card p-5 shadow-sm">
          <div className="flex items-center justify-between">
            <p className="text-sm font-extrabold text-foreground">등록된 종목</p>
            <span className="text-xs text-muted-foreground">
              {count} / {isPro ? "∞" : FREE_LIMIT} · {isPro ? "무제한 Pro" : "Free"}
            </span>
          </div>

          {/* 종목 목록 */}
          <div className="flex flex-col gap-2">
            {showSkeleton ? (
              <div role="status" aria-label="종목 목록 불러오는 중" className="flex flex-col gap-2">
                {[...Array(3)].map((_, i) => (
                  <div key={i} className="flex items-center gap-3 rounded-xl border border-border bg-background px-3.5 py-3">
                    <Skeleton className="size-8 shrink-0 rounded-lg" />
                    <div className="flex flex-1 flex-col gap-1.5">
                      <Skeleton className="h-4 w-24" />
                      <Skeleton className="h-3 w-16" />
                    </div>
                    <Skeleton className="size-9 rounded-md" />
                  </div>
                ))}
              </div>
            ) : !portfolios?.length ? (
              <p className="py-6 text-center text-sm text-muted-foreground">아직 등록된 종목이 없습니다.</p>
            ) : (
              portfolios.map((p) => (
                <div key={p.id} className="flex items-center gap-2.5 rounded-xl border border-border bg-background px-3.5 py-3">
                  <div className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground" aria-hidden>
                    {(p.corp_name ?? p.stock_code).slice(0, 2)}
                  </div>
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-bold text-foreground">{p.corp_name ?? p.stock_code}</p>
                    <p className="font-mono text-xs text-muted-foreground">{p.stock_code}</p>
                  </div>
                  <button
                    type="button"
                    onClick={() => handleDelete(p.id, p.corp_name ?? p.stock_code)}
                    disabled={isDeleting}
                    className="grid size-9 shrink-0 place-items-center rounded-md border border-border bg-background text-muted-foreground transition-colors hover:border-destructive hover:text-destructive disabled:opacity-40"
                    aria-label={`${p.corp_name ?? p.stock_code} 삭제`}
                  >
                    <X className="size-4" />
                  </button>
                </div>
              ))
            )}
          </div>

          {/* 찾는 종목이 없나요? */}
          <div className="flex items-start gap-2.5 rounded-xl border border-primary/20 bg-primary/5 px-4 py-3.5">
            <CircleHelp className="mt-0.5 size-4 shrink-0 text-primary" aria-hidden />
            <div>
              <p className="text-sm font-semibold text-foreground">찾는 종목이 없나요?</p>
              <p className="mt-0.5 text-xs text-muted-foreground">
                커버리지(코스피200+코스닥150) 밖 종목은{" "}
                <span className="font-bold text-primary">추가 등록 요청</span>{" "}
                시 7일 내 검토해드려요.
              </p>
            </div>
          </div>
        </div>
      </div>

      {/* 종목 삭제 확인 AlertDialog */}
      {/* isDeleting 중 닫기 차단 — 뮤테이션 고아 방지 */}
      <AlertDialog open={!!deleteTarget} onOpenChange={(open) => { if (!open && !isDeleting) { setDeleteTarget(null); setDeleteError(null); } }}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>종목 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              &quot;{deleteTarget?.name}&quot;을 포트폴리오에서 삭제하시겠습니까?
              삭제하면 해당 종목의 공시 알림을 받을 수 없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          {deleteError && (
            <p className="px-1 text-sm text-destructive" role="alert">{deleteError}</p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={isDeleting}>취소</AlertDialogCancel>
            <AlertDialogAction variant="destructive" onClick={confirmDelete} disabled={isDeleting}>
              {isDeleting ? "삭제 중..." : "삭제"}
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
