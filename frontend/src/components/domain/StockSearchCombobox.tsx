"use client";

// [목적] 종목명/코드 자동완성 검색 — 실시간 드롭다운 (WAI-ARIA 1.2 Combobox Pattern)
// [이유] 종목 등록 시 직접 코드 입력 대신 검색으로 선택. PortfolioSheet 경유로 알림·온보딩 플로우에서 사용.
// [사이드 임팩트] GET /stocks/search (PUBLIC) 호출. useDebounce(query)로 value-based 디바운스.
//   activeIndex state로 ArrowDown/Up/Enter 키보드 네비. id="sc-option-${i}" (new/page의 stock-option-과 충돌 방지).
//   data 변경 시 activeIndex 리셋(useEffect). 활성 옵션 scrollIntoView(nearest).
// [수정 시 고려사항] 검색 결과 최대 20건. sc-option prefix는 new/page와 공존 시 id 충돌 방지용 — 변경 시 양쪽 맞출 것.
//   onSelect 콜백은 PortfolioSheet → notifications/page, signup/complete/page로 전파됨.

import { useState, useCallback, useRef, useEffect } from "react";
import { Search, ChevronDown } from "lucide-react";
import { useStockSearch } from "@/lib/api/stocks";
import { useDebounce } from "@/lib/hooks/useDebounce";
import type { StockSearchResult } from "@/lib/api/stocks";

interface StockSearchComboboxProps {
  onSelect: (stock: StockSearchResult) => void;
  placeholder?: string;
}

export function StockSearchCombobox({ onSelect, placeholder = "종목명 또는 코드 검색" }: StockSearchComboboxProps) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [activeIndex, setActiveIndex] = useState(-1);
  const activeOptionRef = useRef<HTMLLIElement | null>(null);

  const debouncedQ = useDebounce(query);
  const { data, isLoading } = useStockSearch(debouncedQ);

  useEffect(() => { setActiveIndex(-1); }, [data]);
  useEffect(() => { activeOptionRef.current?.scrollIntoView({ block: "nearest" }); }, [activeIndex]);

  // debounce 대기 중이거나 API 호출 중이면 true
  const isSearching = (query.trim().length >= 1 && query !== debouncedQ) || isLoading;

  const handleChange = useCallback((q: string) => {
    setQuery(q);
    setOpen(q.length > 0);
  }, []);

  const handleSelect = useCallback((stock: StockSearchResult) => {
    setQuery(`${stock.corp_name} (${stock.stock_code})`);
    setOpen(false);
    onSelect(stock);
  }, [onSelect]);

  return (
    <div className="relative">
      <div aria-live="polite" className="sr-only">
        {isSearching ? "검색 중..." : !isSearching && data?.length === 0 && query.trim() ? "검색 결과가 없습니다" : ""}
      </div>
      <div className="flex items-center gap-2.5 rounded-xl border border-border bg-background px-3.5 py-2.5 focus-within:border-primary focus-within:ring-2 focus-within:ring-primary/20">
        <Search className="size-4 shrink-0 text-muted-foreground" aria-hidden />
        <input
          type="search"
          value={query}
          onChange={(e) => handleChange(e.target.value)}
          placeholder={placeholder}
          className="flex-1 bg-transparent text-sm text-foreground placeholder:text-muted-foreground focus:outline-none"
          aria-label="종목 검색"
          aria-expanded={open}
          aria-autocomplete="list"
          aria-controls="stock-search-results"
          aria-haspopup="listbox"
          role="combobox"
          aria-activedescendant={open && activeIndex >= 0 ? `sc-option-${activeIndex}` : undefined}
          onKeyDown={(e) => {
            const len = data?.length ?? 0;
            if (e.key === "ArrowDown") {
              e.preventDefault();
              if (len > 0) setActiveIndex((i) => (i + 1) % len);
            } else if (e.key === "ArrowUp") {
              e.preventDefault();
              if (len > 0) setActiveIndex((i) => (i - 1 + len) % len);
            } else if (e.key === "Enter" && activeIndex >= 0 && data?.[activeIndex]) {
              e.preventDefault();
              handleSelect(data[activeIndex]);
            } else if (e.key === "Escape") {
              setOpen(false);
              setActiveIndex(-1);
            }
          }}
        />
        <ChevronDown className="size-4 shrink-0 text-muted-foreground" aria-hidden />
      </div>

      {open && (
        <ul
          id="stock-search-results"
          role="listbox"
          aria-label="종목 검색 결과"
          className="absolute z-50 mt-1 w-full overflow-hidden rounded-xl border border-border bg-background shadow-md"
        >
          {isSearching && (
            <li role="presentation" className="px-4 py-3 text-sm text-muted-foreground">검색 중...</li>
          )}
          {!isSearching && data?.length === 0 && (
            <li role="presentation" className="px-4 py-3 text-sm text-muted-foreground">검색 결과가 없습니다</li>
          )}
          {!isSearching && data?.map((stock, i) => (
            <li key={stock.stock_code} id={`sc-option-${i}`} role="option" aria-selected={activeIndex === i} ref={activeIndex === i ? activeOptionRef : undefined}>
              <button
                type="button"
                onClick={() => handleSelect(stock)}
                className={`flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none${activeIndex === i ? " bg-muted" : ""}`}
              >
                <div className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground">
                  {(stock.corp_name || stock.stock_code).slice(0, 2)}
                </div>
                <div>
                  <p className="text-sm font-bold text-foreground">{stock.corp_name}</p>
                  <p className="font-mono text-xs text-muted-foreground">
                    {stock.stock_code} · {stock.market}
                  </p>
                </div>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
