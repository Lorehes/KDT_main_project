"use client";

// [목적] 종목명/코드 자동완성 검색 — 실시간 드롭다운
// [이유] 종목 등록 시 직접 코드 입력 대신 검색으로 종목을 선택하는 UX
// [사이드 임팩트] GET /stocks/search (PUBLIC) 호출. 디바운스는 컴포넌트 레벨에서 처리.
//   query !== debouncedQ(debounce 대기 중)일 때 "검색 중..." 표시 — 빈 드롭다운으로 보이는 UX 개선.
// [수정 시 고려사항] 검색 결과는 최대 20건. 미커버 종목은 /stocks/coverage-requests로 요청(W5에서 추가)

import { useState, useCallback, useRef } from "react";
import { Search, ChevronDown } from "lucide-react";
import { useStockSearch } from "@/lib/api/stocks";
import type { StockSearchResult } from "@/lib/api/stocks";

interface StockSearchComboboxProps {
  onSelect: (stock: StockSearchResult) => void;
  placeholder?: string;
}

function useDebounce(value: string, delay = 300) {
  const [debouncedValue, setDebouncedValue] = useState(value);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const debounced = useCallback(
    (v: string) => {
      if (timerRef.current) clearTimeout(timerRef.current);
      timerRef.current = setTimeout(() => setDebouncedValue(v), delay);
    },
    [delay],
  );
  return [debouncedValue, debounced] as const;
}

export function StockSearchCombobox({ onSelect, placeholder = "종목명 또는 코드 검색" }: StockSearchComboboxProps) {
  const [query, setQuery] = useState("");
  const [debouncedQ, setDebounced] = useDebounce("");
  const [open, setOpen] = useState(false);

  const { data, isLoading } = useStockSearch(debouncedQ);

  // debounce 대기 중이거나 API 호출 중이면 true
  const isSearching = (query.trim().length >= 1 && query !== debouncedQ) || isLoading;

  const handleChange = (q: string) => {
    setQuery(q);
    setDebounced(q);
    setOpen(q.length > 0);
  };

  const handleSelect = (stock: StockSearchResult) => {
    setQuery(`${stock.corp_name} (${stock.stock_code})`);
    setOpen(false);
    onSelect(stock);
  };

  return (
    <div className="relative">
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
          role="combobox"
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
            <li className="px-4 py-3 text-sm text-muted-foreground" role="status" aria-live="polite">검색 중...</li>
          )}
          {!isSearching && data?.length === 0 && (
            <li className="px-4 py-3 text-sm text-muted-foreground">검색 결과가 없습니다</li>
          )}
          {!isSearching && data?.map((stock) => (
            <li key={stock.stock_code} role="option" aria-selected={false}>
              <button
                type="button"
                onClick={() => handleSelect(stock)}
                className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-muted focus-visible:bg-muted focus-visible:outline-none"
              >
                <div className="grid size-8 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground">
                  {stock.corp_name.slice(0, 2)}
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
