"use client";

// [목적] 포트폴리오 목록 아이템 — 종목 아이콘·이름·코드·알림 아이콘·수정·삭제 버튼
// [이유] portfolios/page.tsx 인라인 렌더링을 6회 반복(corp_name ?? stock_code)하는 list item을
//   컴포넌트로 추출해 중복 제거(R9). 수정·삭제 핸들러는 부모에서 prop으로 전달.
// [사이드 임팩트] portfolios/page.tsx에서 PortfolioListItem으로 대체 — 렌더 로직 변경 없음.
// [수정 시 고려사항] notify_enabled는 BE PortfolioResponse 미지원 — Bell은 현재 장식용.
//   BE notify_enabled 필드 추가 시 prop으로 받아 BellOff 아이콘 전환 필요.

import { memo } from "react";
import Link from "next/link";
import { Bell, Trash2 } from "lucide-react";
import { Button, buttonVariants } from "@/components/ui/button";
import type { Portfolio } from "@/lib/api/portfolios";

interface Props {
  portfolio: Portfolio;
  onDelete: (id: number, name: string) => void;
  isDeleting: boolean;
}

export const PortfolioListItem = memo(function PortfolioListItem({ portfolio: p, onDelete, isDeleting }: Props) {
  const displayName = p.corp_name ?? p.stock_code;

  return (
    <li className="flex items-center justify-between px-5 py-4">
      <div className="flex items-center gap-3">
        <div
          className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground"
          aria-hidden
        >
          {displayName.slice(0, 2)}
        </div>
        <div>
          <p className="text-sm font-bold text-foreground">{displayName}</p>
          <p className="font-mono text-xs text-muted-foreground">{p.stock_code}</p>
        </div>
      </div>

      <div className="flex items-center gap-2">
        {/* notify_enabled BE 미지원 — 장식용 아이콘. 구현 후 Button+aria-pressed로 교체 */}
        <Bell className="size-4 text-primary" aria-hidden />
        <Link
          href={`/portfolios/new?code=${p.stock_code}&name=${encodeURIComponent(displayName)}&edit=${p.id}`}
          className={buttonVariants({ variant: "ghost", size: "sm" })}
          aria-label={`${displayName} 수정`}
        >
          수정
        </Link>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => onDelete(p.id, displayName)}
          disabled={isDeleting}
          className="text-destructive hover:bg-destructive/10 hover:text-destructive"
          aria-label={`${displayName} 삭제`}
        >
          <Trash2 className="size-4" aria-hidden />
        </Button>
      </div>
    </li>
  );
});
