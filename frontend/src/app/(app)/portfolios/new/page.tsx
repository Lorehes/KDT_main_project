"use client";

// [목적] 종목 관리 페이지(D12/m05·D12b/m05b) — 좌: 검색+CSV업로드 / 우: 등록된 종목+알림 CTA (WAI-ARIA Combobox)
// [이유] 검색과 등록 목록을 패널로 분리해 종목 추가 흐름을 명확히 함. WCAG 2.1 AA 키보드 네비(페르소나 E — 시니어 투자자).
// [사이드 임팩트] usePortfolios·useDeletePortfolio 쿼리. 삭제 후 ["portfolios"] 자동 무효화.
//   activeIndex state로 ArrowDown/Up/Enter 키보드 네비. id="stock-option-${i}" (Combobox의 sc-option과 충돌 없음).
//   searchResults 변경 시 activeIndex 리셋(useEffect). 활성 옵션 scrollIntoView(nearest).
//   CSV 업로드: parsePortfolioCsv → 종목코드 추출 → 확인 UI → importPortfolios() 단일 호출 → invalidateQueries 1회.
//   CSV 개인정보 보호: 종목코드만 추출해 POST — 매수가·수량은 절대 추출·전송·로그 금지(CLAUDE.md §7).
// [수정 시 고려사항] Free 3종목 초과 시 422 BUSINESS_RULE_VIOLATION — atLimit에 isLoading 포함.
//   매수가·수량 console.log 절대 금지(금융 개인정보). 종목 선택 후 /portfolios/add?code=...&name=... 라우팅.
//   Enter 선택 시 atLimit 체크 필수(disabled 상태와 동일 처리). stock-option prefix는 StockSearchCombobox와 맞출 것.
//   importPortfolios 5xx 완전 실패 시 toast.error 전체 오류 처리 — skippedFailed 범주 消滅(단일 호출로 대체).
//   ImportPortfoliosResult 카테고리 추가 시 handleBulkRegister parts 배열 분기도 동기화.
//   2026-06-26 Lorehes: handleBulkRegister N-loop 제거 → importPortfolios() 단일 호출로 교체, apiClient/ApiException import 제거.

import { useCallback, useState, useRef, useEffect, useMemo } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { toast } from "sonner";
import { Search, Upload, X, CircleHelp } from "lucide-react";
import { usePortfolios, useDeletePortfolio, importPortfolios } from "@/lib/api/portfolios";
import { useStockSearch } from "@/lib/api/stocks";
import { useTierCheck } from "@/lib/hooks/useTierCheck";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { useDebounce } from "@/lib/hooks/useDebounce";
import { parsePortfolioCsv } from "@/lib/csv/parsePortfolioCsv";
import { Skeleton } from "@/components/ui/skeleton";
import { Button, buttonVariants } from "@/components/ui/button";
import {
  AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle,
  AlertDialogDescription, AlertDialogFooter, AlertDialogAction, AlertDialogCancel,
} from "@/components/ui/alert-dialog";
import type { StockSearchResult } from "@/lib/api/stocks";

const FREE_LIMIT = 3;

type CsvPhase = "idle" | "parsing" | "review" | "registering";

interface CsvItem {
  code: string;
  isDuplicate: boolean; // already in portfolios — skip pre-registration
  checked: boolean;     // user selection when Free tier quota is exceeded
}

export default function PortfoliosNewPage() {
  const router = useRouter();
  const qc = useQueryClient();
  const { isPro } = useTierCheck();
  const { data: portfolios, isLoading } = usePortfolios();
  const { mutate: deletePortfolio, isPending: isDeleting } = useDeletePortfolio();
  const showSkeleton = useDelayedLoading(isLoading);
  const [deleteTarget, setDeleteTarget] = useState<{ id: number; name: string } | null>(null);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  const [searchInput, setSearchInput] = useState("");
  const [dropdownOpen, setDropdownOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const searchContainerRef = useRef<HTMLDivElement>(null);
  const activeOptionRef = useRef<HTMLLIElement | null>(null);
  const debouncedQuery = useDebounce(searchInput);
  const { data: searchResults, isLoading: isSearching } = useStockSearch(debouncedQuery);

  // CSV upload state
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [isDragging, setIsDragging] = useState(false);
  const [csvPhase, setCsvPhase] = useState<CsvPhase>("idle");
  const [csvError, setCsvError] = useState<string | null>(null);
  const [csvItems, setCsvItems] = useState<CsvItem[]>([]);

  useEffect(() => { setActiveIndex(-1); }, [searchResults]);
  useEffect(() => { activeOptionRef.current?.scrollIntoView({ block: "nearest" }); }, [activeIndex]);

  const count = portfolios?.length ?? 0;
  const atLimit = isLoading || (!isPro && count >= FREE_LIMIT);
  const remainingSlots = isPro ? Infinity : Math.max(0, FREE_LIMIT - count);

  // Derived CSV review state
  const validCsvCount = useMemo(() => csvItems.filter(i => !i.isDuplicate).length, [csvItems]);
  // Show checkboxes only when Free user has more valid codes than remaining slots (and quota > 0)
  const showCheckboxes = !isPro && remainingSlots > 0 && validCsvCount > remainingSlots;
  const toRegisterCount = useMemo(() => csvItems.filter(i => !i.isDuplicate && i.checked).length, [csvItems]);

  const handleSelect = useCallback((stock: StockSearchResult) => {
    setSearchInput("");
    setDropdownOpen(false);
    router.push(`/portfolios/add?code=${stock.stock_code}&name=${encodeURIComponent(stock.corp_name)}&market=${stock.market}`);
  }, [router]);

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

  const resetCsvState = useCallback(() => {
    setCsvPhase("idle");
    setCsvItems([]);
    setCsvError(null);
  }, []);

  const handleFileAccepted = useCallback(async (file: File) => {
    if (!file.name.toLowerCase().endsWith(".csv")) {
      setCsvError("CSV 파일(.csv)만 지원합니다.");
      return;
    }
    setCsvError(null);
    setCsvPhase("parsing");
    try {
      const codes = await parsePortfolioCsv(file);
      if (codes.length === 0) {
        setCsvError("종목코드를 찾을 수 없습니다. 다른 파일을 사용해주세요.");
        setCsvPhase("idle");
        return;
      }
      const portfolioSet = new Set(portfolios?.map(p => p.stock_code) ?? []);
      const remaining = isPro ? Infinity : Math.max(0, FREE_LIMIT - (portfolios?.length ?? 0));
      const items: CsvItem[] = [];
      let nonDupCount = 0;
      for (const code of codes) {
        const isDuplicate = portfolioSet.has(code);
        // Auto-check non-duplicates up to remaining quota; Pro users get all checked
        const checked = !isDuplicate && (isPro || nonDupCount < remaining);
        if (!isDuplicate) nonDupCount++;
        items.push({ code, isDuplicate, checked });
      }
      setCsvItems(items);
      setCsvPhase("review");
    } catch {
      setCsvError("CSV 파싱에 실패했습니다. 파일을 확인해주세요.");
      setCsvPhase("idle");
    }
  }, [portfolios, isPro]);

  const handleDrop = useCallback((e: React.DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setIsDragging(false);
    if (csvPhase !== "idle") return; // parsing 중 중복 drop 차단 — 비결정적 상태 덮어쓰기 방지
    const file = e.dataTransfer.files[0];
    if (file) void handleFileAccepted(file);
  }, [csvPhase, handleFileAccepted]);

  const handleFileChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      void handleFileAccepted(file);
      e.target.value = ""; // reset so same file triggers onChange again
    }
  }, [handleFileAccepted]);

  const toggleCsvItem = useCallback((code: string) => {
    const remaining = isPro ? Infinity : Math.max(0, FREE_LIMIT - (portfolios?.length ?? 0));
    setCsvItems(prev => {
      const currentChecked = prev.filter(i => !i.isDuplicate && i.checked).length;
      return prev.map(item => {
        if (item.code !== code || item.isDuplicate) return item;
        // Block checking when at Free limit and this item is unchecked
        if (!item.checked && currentChecked >= remaining) return item;
        return { ...item, checked: !item.checked };
      });
    });
  }, [isPro, portfolios]);

  const handleBulkRegister = useCallback(async () => {
    const toRegister = csvItems.filter(i => !i.isDuplicate && i.checked).map(i => i.code);
    if (!toRegister.length) { resetCsvState(); return; }
    setCsvPhase("registering");
    try {
      // stock_codes only — avg_buy_price/quantity NOT sent (CSV financial PII protection, CLAUDE.md §7)
      const result = await importPortfolios(toRegister);
      await qc.invalidateQueries({ queryKey: ["portfolios"] });
      const parts: string[] = [];
      if (result.added.length)               parts.push(`${result.added.length}종목 등록됨`);
      if (result.skipped_duplicate.length)   parts.push(`${result.skipped_duplicate.length} 중복`);
      if (result.skipped_unsupported.length) parts.push(`${result.skipped_unsupported.length} 미지원`);
      if (result.skipped_limit.length)       parts.push(`${result.skipped_limit.length} 한도 초과`);
      result.added.length > 0
        ? toast.success(parts.join(" · "))
        : toast.info(parts.join(" · ") || "등록된 종목이 없습니다.");
    } catch {
      toast.error("일괄 등록에 실패했습니다. 잠시 후 다시 시도해주세요.");
    }
    resetCsvState();
  }, [csvItems, qc, resetCsvState]);

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
                  onKeyDown={(e) => {
                    const len = searchResults?.length ?? 0;
                    if (e.key === "ArrowDown") {
                      e.preventDefault();
                      if (len > 0) { setDropdownOpen(true); setActiveIndex((i) => (i + 1) % len); }
                    } else if (e.key === "ArrowUp") {
                      e.preventDefault();
                      if (len > 0) setActiveIndex((i) => (i - 1 + len) % len);
                    } else if (e.key === "Enter" && activeIndex >= 0 && searchResults?.[activeIndex] && !atLimit) {
                      e.preventDefault();
                      handleSelect(searchResults[activeIndex]);
                    } else if (e.key === "Escape") {
                      setDropdownOpen(false);
                      setActiveIndex(-1);
                    }
                  }}
                  placeholder={atLimit ? "Free 플랜 3종목 초과 — Pro 업그레이드 후 추가" : "종목명 또는 코드 검색"}
                  className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
                  aria-label="종목 검색"
                  aria-expanded={dropdownOpen}
                  aria-autocomplete="list"
                  aria-controls="stock-dropdown"
                  aria-haspopup="listbox"
                  role="combobox"
                  aria-activedescendant={dropdownOpen && activeIndex >= 0 ? `stock-option-${activeIndex}` : undefined}
                />
              </div>
              <Button className="shrink-0" onClick={() => { if (searchInput.trim()) setDropdownOpen(true); }}>검색</Button>
            </div>

            {/* 드롭다운 */}
            <div aria-live="polite" className="sr-only">
              {isSearching ? "검색 중..." : !isSearching && (searchResults?.length === 0) && searchInput.trim() ? "검색 결과가 없습니다" : ""}
            </div>
            {dropdownOpen && searchInput.trim() && (
              <ul
                id="stock-dropdown"
                role="listbox"
                aria-label="종목 검색 결과"
                className="absolute left-0 right-0 top-full z-50 mt-1 overflow-hidden rounded-xl border border-border bg-background shadow-md"
              >
                {isSearching && (
                  <li role="presentation" className="px-4 py-3 text-sm text-muted-foreground">검색 중...</li>
                )}
                {!isSearching && searchResults?.length === 0 && (
                  <li role="presentation" className="px-4 py-3 text-sm text-muted-foreground">검색 결과가 없습니다.</li>
                )}
                {!isSearching && searchResults?.map((stock, i) => (
                  <li
                    key={stock.stock_code}
                    id={`stock-option-${i}`}
                    role="option"
                    aria-selected={activeIndex === i}
                    ref={activeIndex === i ? activeOptionRef : undefined}
                  >
                    <div className={`flex items-center justify-between px-4 py-3 hover:bg-muted${activeIndex === i ? " bg-muted" : ""}`}>
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

          {/* CSV 업로드존 (idle·parsing) / 리뷰·등록 패널 (review·registering) */}
          {csvPhase === "idle" || csvPhase === "parsing" ? (
            <div
              className={`flex flex-col items-center justify-center gap-2 rounded-xl border-2 border-dashed py-8 text-center transition-colors ${
                isDragging ? "border-primary bg-primary/5" : "border-border bg-background"
              } ${csvPhase === "idle" ? "cursor-pointer" : "cursor-wait"}`}
              onDragOver={(e) => { e.preventDefault(); if (csvPhase === "idle") setIsDragging(true); }}
              onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setIsDragging(false); }}
              onDrop={handleDrop}
              onClick={() => { if (csvPhase === "idle") fileInputRef.current?.click(); }}
              role="button"
              tabIndex={csvPhase === "idle" ? 0 : -1}
              aria-label="증권사 CSV 파일 업로드 — 클릭하거나 파일을 끌어다 놓으세요"
              onKeyDown={(e) => {
                if ((e.key === "Enter" || e.key === " ") && csvPhase === "idle") {
                  e.preventDefault();
                  fileInputRef.current?.click();
                }
              }}
            >
              <input
                ref={fileInputRef}
                type="file"
                accept=".csv"
                className="sr-only"
                aria-hidden
                tabIndex={-1}
                onChange={handleFileChange}
              />
              <div className="grid size-10 place-items-center rounded-xl border border-border bg-card">
                <Upload className="size-5 text-muted-foreground" aria-hidden />
              </div>
              {csvPhase === "parsing" ? (
                <p className="text-sm font-semibold text-foreground" aria-live="polite">CSV 파일 분석 중...</p>
              ) : (
                <>
                  <p className="text-sm font-semibold text-foreground">증권사 거래내역 CSV 업로드</p>
                  <p className="text-xs text-muted-foreground">파일을 끌어다 놓거나 클릭하세요 (EUC-KR·UTF-8 지원)</p>
                  {csvError && (
                    <p className="text-xs text-destructive" role="alert">{csvError}</p>
                  )}
                </>
              )}
            </div>
          ) : (
            /* 리뷰 / 등록 진행 패널 */
            <div className="flex flex-col gap-3 rounded-xl border border-border bg-background p-4">
              <div className="flex items-center justify-between">
                <p className="text-sm font-semibold text-foreground">
                  추출된 종목 {csvItems.length}개
                  {validCsvCount > 0 && (
                    <span className="ml-1.5 text-xs font-normal text-muted-foreground">
                      ({validCsvCount}개 신규 · {csvItems.length - validCsvCount}개 중복)
                    </span>
                  )}
                </p>
                <button
                  type="button"
                  onClick={resetCsvState}
                  disabled={csvPhase === "registering"}
                  className="rounded text-xs text-muted-foreground hover:text-foreground disabled:opacity-40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
                  aria-label="CSV 업로드 취소"
                >
                  취소
                </button>
              </div>

              {/* 잔여 한도 안내 (Free 전용) */}
              {!isPro && (
                <div className="rounded-lg bg-muted px-3 py-2 text-xs text-muted-foreground">
                  잔여 등록 가능:{" "}
                  <span className="font-bold text-foreground">
                    {remainingSlots === 0 ? "없음" : `${remainingSlots}종목`}
                  </span>{" "}
                  (Free 플랜 최대 {FREE_LIMIT}종목)
                  {showCheckboxes && (
                    <span> — 등록할 종목을 {remainingSlots}개 선택하세요</span>
                  )}
                </div>
              )}

              {/* 종목 목록 */}
              <ul
                className="flex max-h-48 flex-col gap-0.5 overflow-y-auto"
                role="list"
                aria-label="추출된 종목 목록"
              >
                {csvItems.map(item => (
                  <li
                    key={item.code}
                    className={`flex items-center gap-2.5 rounded-lg px-2 py-1.5 ${
                      item.isDuplicate ? "opacity-50" : ""
                    }`}
                  >
                    {showCheckboxes && !item.isDuplicate && (
                      <input
                        type="checkbox"
                        id={`csv-${item.code}`}
                        checked={item.checked}
                        onChange={() => toggleCsvItem(item.code)}
                        disabled={
                          csvPhase === "registering" ||
                          (!item.checked && toRegisterCount >= remainingSlots)
                        }
                        className="size-4 shrink-0 accent-primary"
                        aria-label={`${item.code} 등록 선택`}
                      />
                    )}
                    <span className="flex-1 font-mono text-sm text-foreground">{item.code}</span>
                    {item.isDuplicate && (
                      <span className="rounded bg-muted px-1.5 py-0.5 text-xs font-medium text-muted-foreground">
                        이미 등록됨
                      </span>
                    )}
                  </li>
                ))}
              </ul>

              {/* Free 한도 초과 업그레이드 안내 */}
              {!isPro && remainingSlots === 0 && validCsvCount > 0 && (
                <p className="text-xs text-muted-foreground">
                  Free 플랜 등록 한도에 도달했습니다.{" "}
                  <Link href="/pricing" className="font-bold text-primary hover:underline">
                    Pro로 업그레이드 →
                  </Link>
                </p>
              )}

              {/* 액션 버튼 */}
              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  className="flex-1"
                  onClick={resetCsvState}
                  disabled={csvPhase === "registering"}
                >
                  취소
                </Button>
                <Button
                  size="sm"
                  className="flex-1"
                  onClick={() => void handleBulkRegister()}
                  disabled={csvPhase === "registering" || toRegisterCount === 0}
                  aria-label={toRegisterCount > 0 ? `${toRegisterCount}종목 포트폴리오에 등록` : "등록할 종목 없음"}
                >
                  {csvPhase === "registering"
                    ? "등록 중..."
                    : toRegisterCount > 0
                    ? `${toRegisterCount}종목 등록`
                    : "등록할 종목 없음"}
                </Button>
              </div>
            </div>
          )}
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
