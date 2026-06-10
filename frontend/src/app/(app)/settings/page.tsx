"use client";

// [목적] 마이페이지·설정(D16/m24) — 프로필·현재 플랜·계정/보안 설정 통합 화면
// [이유] 상단 아바타 드롭다운(D26)과 모바일 햄버거 드로어의 "마이페이지"가 이 페이지로 연결
// [사이드 임팩트] useAuthStore·PATCH /users/me 의존. 닉네임 수정 후 authStore 갱신 필요
// [수정 시 고려사항] 회원 탈퇴는 DELETE /users/me(soft delete). 현재 미구현 — "문의" 안내로 대체.
//   비밀번호 변경은 별도 이메일 인증 플로우 필요 — 향후 추가

import { useState } from "react";
import Link from "next/link";
import { Bell, CreditCard, Lock, LogOut, User, ChevronRight } from "lucide-react";
import { useAuthStore } from "@/lib/stores/authStore";
import { apiClient } from "@/lib/api/client";
import { buttonVariants } from "@/components/ui/button";
import { SUPPORT_EMAIL } from "@/lib/constants";

const TIER_LABEL: Record<string, string> = {
  FREE:    "Free",
  PRO:     "Pro",
  PREMIUM: "Premium",
};

const TIER_PRICE: Record<string, string> = {
  FREE:    "무료",
  PRO:     "₩9,900/월",
  PREMIUM: "₩29,900/월",
};

export default function SettingsPage() {
  const { user, fetchMe, logout } = useAuthStore();
  const [nickname, setNickname] = useState(user?.nickname ?? "");
  const [editing, setEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  const handleSaveNickname = async () => {
    if (!nickname.trim()) return;
    setIsSaving(true);
    try {
      await apiClient("/users/me", { method: "PATCH", body: JSON.stringify({ nickname }) });
      await fetchMe();
      setEditing(false);
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } finally {
      setIsSaving(false);
    }
  };

  const tier = user?.tier ?? "FREE";

  return (
    <div className="mx-auto flex max-w-xl flex-col gap-6">
      <h1 className="text-2xl font-extrabold tracking-tight text-foreground">마이페이지</h1>

      {/* 프로필 카드 */}
      <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="profile-heading">
        <h2 id="profile-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">프로필</h2>

        <div className="flex items-center gap-4">
          <div className="grid size-14 place-items-center rounded-2xl bg-[color:var(--color-brand-navy)] text-2xl font-extrabold text-white" aria-hidden>
            {user?.nickname?.[0] ?? "?"}
          </div>
          <div className="flex-1 min-w-0">
            {editing ? (
              <div className="flex items-center gap-2">
                <input
                  type="text"
                  value={nickname}
                  onChange={(e) => setNickname(e.target.value)}
                  maxLength={20}
                  autoFocus
                  className="flex-1 rounded-lg border border-primary bg-background px-3 py-1.5 text-sm font-bold text-foreground focus:outline-none"
                  aria-label="닉네임 입력"
                />
                <button type="button" onClick={handleSaveNickname} disabled={isSaving}
                  className={buttonVariants({ size: "sm" }) + " shrink-0 text-xs"}>
                  {isSaving ? "저장 중" : "저장"}
                </button>
                <button type="button" onClick={() => setEditing(false)}
                  className={buttonVariants({ variant: "ghost", size: "sm" }) + " shrink-0 text-xs"}>
                  취소
                </button>
              </div>
            ) : (
              <div className="flex items-center gap-2">
                <p className="text-base font-extrabold text-foreground">{user?.nickname ?? "사용자"}</p>
                <button type="button" onClick={() => setEditing(true)}
                  className="text-xs font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
                  수정
                </button>
                {saved && <span className="text-xs text-[color:var(--color-sentiment-positive)]">저장됨 ✓</span>}
              </div>
            )}
            <p className="text-sm text-muted-foreground">{user?.email}</p>
          </div>
        </div>
      </section>

      {/* 현재 플랜 */}
      <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="plan-heading">
        <h2 id="plan-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">구독 플랜</h2>
        <div className="flex items-center justify-between">
          <div>
            <p className="text-base font-extrabold text-foreground">{TIER_LABEL[tier]} 플랜</p>
            <p className="text-sm text-muted-foreground">{TIER_PRICE[tier]}</p>
            {user?.tier_expires_at && (
              <p className="mt-0.5 text-xs text-muted-foreground">
                다음 결제: {new Date(user.tier_expires_at).toLocaleDateString("ko-KR")}
              </p>
            )}
          </div>
          <Link href="/pricing" className={buttonVariants({ variant: "outline", size: "sm" })}>
            {tier === "FREE" ? "업그레이드" : "구독 관리"}
          </Link>
        </div>
      </section>

      {/* 설정 메뉴 */}
      <section className="rounded-2xl border border-border bg-card shadow-sm overflow-hidden" aria-labelledby="settings-heading">
        <h2 id="settings-heading" className="sr-only">설정 메뉴</h2>
        {[
          { icon: User,      label: "프로필 정보",  desc: "닉네임·투자 성향",   href: null,                   action: () => setEditing(true) },
          { icon: Bell,      label: "알림 설정",    desc: "채널·빈도·유형",    href: "/notifications/settings", action: null },
          { icon: CreditCard, label: "구독 관리",   desc: "플랜·결제 수단",    href: "/pricing",               action: null },
          { icon: Lock,      label: "계정 보안",    desc: "비밀번호·연결 계정", href: null,                    action: () => alert("비밀번호 변경 기능 준비 중") },
        ].map(({ icon: Icon, label, desc, href, action }) => {
          const content = (
            <div className="flex items-center gap-4 px-5 py-4 transition-colors hover:bg-muted">
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted">
                <Icon className="size-4 text-muted-foreground" aria-hidden />
              </div>
              <div className="flex-1">
                <p className="text-sm font-bold text-foreground">{label}</p>
                <p className="text-xs text-muted-foreground">{desc}</p>
              </div>
              <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
            </div>
          );
          return href ? (
            <Link key={label} href={href} className="block border-b border-border last:border-b-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset">
              {content}
            </Link>
          ) : (
            <button key={label} type="button" onClick={action ?? undefined} className="w-full border-b border-border text-left last:border-b-0 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset">
              {content}
            </button>
          );
        })}
      </section>

      {/* 계정 탈퇴·로그아웃 */}
      <section className="rounded-2xl border border-border bg-card shadow-sm overflow-hidden">
        <button
          type="button"
          onClick={logout}
          className="flex w-full items-center gap-4 border-b border-border px-5 py-4 transition-colors hover:bg-destructive/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
        >
          <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-destructive/10">
            <LogOut className="size-4 text-destructive" aria-hidden />
          </div>
          <span className="text-sm font-bold text-destructive">로그아웃</span>
        </button>
        <div className="px-5 py-3.5">
          <p className="text-xs text-muted-foreground">
            회원 탈퇴를 원하시면{" "}
            <a href={`mailto:${SUPPORT_EMAIL}`} className="font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
              고객지원
            </a>
            에 문의해 주세요.
          </p>
        </div>
      </section>
    </div>
  );
}
