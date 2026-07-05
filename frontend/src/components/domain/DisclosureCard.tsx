"use client";

// [목적] 공시 피드 카드 — Progressive Disclosure(펼치기/접기) 패턴
// [이유] 대시보드·공시 피드에서 많은 공시를 스캔 가능하게. 초기 접힌 상태 → 클릭 시 요약·근거 노출
// [사이드 임팩트] SentimentBadge·DisclaimerNotice와 조합. 카드 클릭 시 /disclosures/[id]로 이동.
//   날짜는 formatDisclosureDate(공용)로 표시 — 대시보드·포트폴리오와 표기 통일(원시 YYYYMMDD 노출 제거).
// [수정 시 고려사항] open 상태는 완전 펼침(인라인 요약). 더 깊은 분석은 상세 페이지로 이동.
//   모바일에서 카드 너비는 100%. 웹에서는 테이블 행 스타일로도 사용.
//   <time dateTime>은 toIsoDate로 ISO 유지(기계 판독/접근성) — 표시 텍스트만 사람이 읽는 포맷.

import { useState } from "react";
import Link from "next/link";
import { ChevronDown, ChevronUp, ExternalLink } from "lucide-react";
import { SentimentBadge } from "./SentimentBadge";
import { ConfidenceMeter } from "./ConfidenceMeter";
import { DisclaimerNotice } from "./DisclaimerNotice";
import { cn } from "@/lib/utils";
import { formatDisclosureDate, toIsoDate } from "@/lib/date/formatDisclosureDate";
import type { Disclosure } from "@/lib/api/disclosures";

interface DisclosureCardProps {
  disclosure: Disclosure;
  className?: string;
}

export function DisclosureCard({ disclosure, className }: DisclosureCardProps) {
  const [open, setOpen] = useState(false);

  const hasSentiment = disclosure.sentiment !== undefined;
  const isWithheld = disclosure.is_withheld ?? false;

  return (
    <article
      className={cn(
        "overflow-hidden rounded-2xl border border-border bg-card transition-shadow",
        open && "border-primary shadow-[0_0_0_3px_color-mix(in_oklch,var(--primary)_15%,transparent)]",
        className,
      )}
    >
      {/* 접힌 상태 헤더 */}
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-start gap-3 p-3.5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
        aria-expanded={open}
        aria-controls={`disc-detail-${disclosure.id}`}
      >
        {/* 회사 로고 자리 */}
        <div
          className="grid size-9 shrink-0 place-items-center rounded-lg bg-primary font-extrabold text-xs text-primary-foreground"
          aria-hidden
        >
          {disclosure.corp_name.slice(0, 2)}
        </div>

        <div className="min-w-0 flex-1">
          <div className="mb-1 flex flex-wrap items-center gap-1.5">
            <span className="text-sm font-bold text-foreground">{disclosure.corp_name}</span>
            <span className="font-mono text-xs text-muted-foreground">{disclosure.stock_code}</span>
            {hasSentiment && (
              <SentimentBadge
                sentiment={disclosure.sentiment!}
                isWithheld={isWithheld}
                size="sm"
              />
            )}
          </div>
          <p className="line-clamp-2 text-sm text-muted-foreground">{disclosure.report_nm}</p>
        </div>

        <div className="flex shrink-0 flex-col items-end gap-1">
          <time className="text-xs text-muted-foreground" dateTime={toIsoDate(disclosure.rcept_dt)}>
            {formatDisclosureDate(disclosure.rcept_dt)}
          </time>
          <div
            className={cn(
              "grid size-6 place-items-center rounded-md transition-colors",
              open ? "bg-primary/10 text-primary" : "bg-muted text-muted-foreground",
            )}
            aria-hidden
          >
            {open ? <ChevronUp className="size-4" /> : <ChevronDown className="size-4" />}
          </div>
        </div>
      </button>

      {/* 펼쳐진 상세 */}
      {open && (
        <div
          id={`disc-detail-${disclosure.id}`}
          className="flex flex-col gap-3 border-t border-border px-3.5 py-3.5"
        >
          {disclosure.summary && (
            <p className="text-sm leading-relaxed text-foreground">{disclosure.summary}</p>
          )}

          {disclosure.confidence !== undefined && (
            <ConfidenceMeter confidence={disclosure.confidence} />
          )}

          <div className="flex items-center gap-3">
            <Link
              href={`/disclosures/${disclosure.id}`}
              className="text-sm font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
            >
              자세한 분석 보기 →
            </Link>
            {disclosure.attachment_url && (
              <a
                href={disclosure.attachment_url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-1 text-xs text-muted-foreground hover:text-foreground"
                aria-label="DART 원문 열기 (새 탭)"
              >
                <ExternalLink className="size-3.5" aria-hidden />
                DART 원문
              </a>
            )}
          </div>

          <DisclaimerNotice />
        </div>
      )}
    </article>
  );
}
