"use client";

// [목적] 알림 모달/시트(D23/m25) — 벨 클릭 시 최근 알림 4건 팝오버(웹) / 상단 시트(모바일)
// [이유] TopBar 벨 버튼에서 즉시 최근 알림을 확인. uiStore.notifModalOpen으로 전역 제어
// [사이드 임팩트] useNotifications({size:4}) 쿼리 사용. 읽음 처리는 로컬 Set으로 임시 관리
//   (백엔드 is_read 컬럼 미존재 — W6 Tech Review 결정)
// [수정 시 고려사항] 웹: Popover(relative+absolute). 모바일: Sheet(top). 읽음 API 추가 시 Set → PATCH 교체.
//   Popover 바깥 클릭 닫힘은 useEffect + document click listener로 처리

import { useEffect, useRef, useState } from "react";
import Link from "next/link";
import { Bell } from "lucide-react";
import { useNotifications } from "@/lib/api/notifications";
import { useUIStore } from "@/lib/stores/uiStore";
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import { cn } from "@/lib/utils";

export function NotificationModal() {
  const { notifModalOpen, toggleNotifModal } = useUIStore();
  const { data } = useNotifications({ size: 4 });
  const notifications = data?.content ?? [];

  // 로컬 읽음 상태 — 백엔드 is_read 미구현으로 클라이언트 임시 처리
  const [readIds, setReadIds] = useState<Set<number>>(new Set());

  const popoverRef = useRef<HTMLDivElement>(null);

  // 팝오버 바깥 클릭 닫기
  useEffect(() => {
    if (!notifModalOpen) return;
    const handler = (e: MouseEvent) => {
      if (popoverRef.current && !popoverRef.current.contains(e.target as Node)) {
        toggleNotifModal();
      }
    };
    document.addEventListener("mousedown", handler);
    return () => document.removeEventListener("mousedown", handler);
  }, [notifModalOpen, toggleNotifModal]);

  const markAllRead = () => setReadIds(new Set(notifications.map((n) => n.id)));

  if (!notifModalOpen) return null;

  return (
    <div
      ref={popoverRef}
      role="dialog"
      aria-label="최근 알림"
      aria-modal="true"
      className="absolute right-4 top-[68px] z-50 w-96 overflow-hidden rounded-2xl border border-border bg-background shadow-2xl"
    >
      {/* 팝 화살표 */}
      <span className="absolute -top-1.5 right-8 size-3 rotate-45 border-l border-t border-border bg-background" aria-hidden />

      {/* 헤더 */}
      <div className="flex items-center justify-between border-b border-border px-4 py-3.5">
        <div className="flex items-center gap-2">
          <span className="text-base font-extrabold text-foreground">알림</span>
          {notifications.filter((n) => !readIds.has(n.id)).length > 0 && (
            <span className="inline-flex h-5 min-w-[20px] items-center justify-center rounded-full bg-[color:var(--color-sentiment-negative)] px-1.5 text-[10px] font-extrabold text-white">
              {notifications.filter((n) => !readIds.has(n.id)).length}
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={markAllRead}
          className="text-xs font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
          모두 읽음
        </button>
      </div>

      {/* 알림 목록 */}
      <ul role="list" className="divide-y divide-border">
        {notifications.length === 0 ? (
          <li className="py-8 text-center text-sm text-muted-foreground">최근 알림이 없습니다.</li>
        ) : (
          notifications.map((notif) => {
            const isRead = readIds.has(notif.id);
            return (
              <li key={notif.id}>
                <Link
                  href={`/disclosures/${notif.disclosure_id}`}
                  onClick={() => {
                    setReadIds((prev) => new Set([...prev, notif.id]));
                    toggleNotifModal();
                  }}
                  className="flex gap-3 px-4 py-3.5 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
                >
                  <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-primary font-extrabold text-xs text-primary-foreground" aria-hidden>
                    {notif.corp_name.slice(0, 2)}
                  </div>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-1.5">
                      <span className="text-sm font-bold text-foreground">{notif.corp_name}</span>
                      <SentimentBadge sentiment={notif.sentiment} size="sm" />
                    </div>
                    <p className="mt-0.5 line-clamp-2 text-xs text-muted-foreground">{notif.report_nm}</p>
                    <time className="mt-1 block text-[11px] text-muted-foreground" dateTime={notif.created_at}>
                      {new Date(notif.created_at).toLocaleString("ko-KR", { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" })}
                    </time>
                  </div>
                  {!isRead && (
                    <span className="mt-1.5 size-2.5 shrink-0 rounded-full bg-primary" aria-label="안읽음" />
                  )}
                </Link>
              </li>
            );
          })
        )}
      </ul>

      {/* 하단 - 전체 보기 */}
      <div className="border-t border-border px-4 py-3 text-center">
        <Link
          href="/notifications"
          onClick={toggleNotifModal}
          className="inline-flex items-center gap-1.5 text-sm font-extrabold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
        >
          <Bell className="size-3.5" aria-hidden />
          전체 알림 보기
        </Link>
      </div>
    </div>
  );
}
