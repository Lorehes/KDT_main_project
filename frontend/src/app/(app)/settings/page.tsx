"use client";

// [목적] 마이페이지·설정(D16/m24) — 프로필·현재 플랜·계정/보안 설정 통합 화면
// [이유] 상단 아바타 드롭다운(D26)과 모바일 햄버거 드로어의 "마이페이지"가 이 페이지로 연결
// [사이드 임팩트] useAuthStore·PATCH /users/me 의존. 닉네임 수정 후 authStore 갱신 필요.
//   nickname state는 useEffect로 user.nickname 동기화 — 마운트 시 user가 null인 경우(fetchMe 지연) 대응.
//   savedTimerRef로 setTimeout 관리 — 언마운트 시 clearTimeout으로 누수 방지.
// [수정 시 고려사항] 회원 탈퇴는 DELETE /users/me(soft delete). 현재 미구현 — "문의" 안내로 대체.
//   비밀번호 변경은 별도 이메일 인증 플로우 필요 — 향후 추가.
//   미구현 기능(데이터 다운로드·보안 설정)은 toast.info로 안내 중 — 구현 시 toast 제거 후 실제 로직 연결.

import { useState, useEffect, useRef } from "react";
import Link from "next/link";
import { Bell, CreditCard, Lock, LogOut, User, ChevronRight, Shield, Download, Trash2, HelpCircle } from "lucide-react";
import { useAuthStore } from "@/lib/stores/authStore";
import { apiClient } from "@/lib/api/client";
import { toast } from "sonner";
import { buttonVariants } from "@/components/ui/button";
import { SUPPORT_EMAIL, TIER_LABEL, TIER_PRICE } from "@/lib/constants";

const APP_VERSION = process.env.NEXT_PUBLIC_APP_VERSION ?? "0.0.0";

export default function SettingsPage() {
  const { user, fetchMe, logout } = useAuthStore();
  const [nickname, setNickname] = useState(user?.nickname ?? "");
  const [editing, setEditing] = useState(false);
  const [isSaving, setIsSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // user 로딩 완료 후 nickname state 동기화 — 마운트 시 user가 null인 stale 초기값 보정
  useEffect(() => {
    if (user?.nickname) setNickname(user.nickname);
  }, [user?.nickname]);

  // 언마운트 시 savedTimer 정리
  useEffect(() => () => { if (savedTimerRef.current) clearTimeout(savedTimerRef.current); }, []);

  const handleSaveNickname = async () => {
    if (!nickname.trim()) return;
    setIsSaving(true);
    try {
      await apiClient("/users/me", { method: "PATCH", body: JSON.stringify({ nickname }) });
      await fetchMe();
      setEditing(false);
      setSaved(true);
      if (savedTimerRef.current) clearTimeout(savedTimerRef.current);
      savedTimerRef.current = setTimeout(() => setSaved(false), 2000);
    } catch {
      toast.error("닉네임 저장에 실패했습니다. 잠시 후 다시 시도해주세요.");
    } finally {
      setIsSaving(false);
    }
  };

  const tier = user?.tier ?? "FREE";

  return (
    <div className="mx-auto max-w-4xl">
      <h1 className="mb-6 text-2xl font-extrabold tracking-tight text-foreground">마이페이지</h1>

      <div className="flex flex-col gap-6">
        {/* ── 상단 행: 프로필 | 구독 플랜 ── */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2">
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
                    <button type="button" onClick={() => setEditing(false)} disabled={isSaving}
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

          {/* 구독 플랜 카드 */}
          <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="plan-heading">
            <h2 id="plan-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">구독 플랜</h2>
            <div className="flex items-center justify-between">
              <div>
                <p className="text-base font-extrabold text-foreground">{TIER_LABEL[tier]} 플랜</p>
                <p className="text-sm text-muted-foreground">{TIER_PRICE[tier]}</p>
                {user?.tier_expires_at && (() => {
                  const d = new Date(user.tier_expires_at);
                  return !isNaN(d.getTime()) ? (
                    <p className="mt-0.5 text-xs text-muted-foreground">
                      다음 결제: {d.toLocaleDateString("ko-KR")}
                    </p>
                  ) : null;
                })()}
              </div>
              <Link href="/pricing" className={buttonVariants({ variant: "outline", size: "sm" })}>
                {tier === "FREE" ? "업그레이드" : "구독 관리"}
              </Link>
            </div>
          </section>
        </div>

        {/* ── 하단 2열: 설정 메뉴 | 개인정보·지원 ── */}
        <div className="grid grid-cols-1 gap-6 lg:grid-cols-2 lg:items-start">
          {/* 좌측 컬럼 */}
          <div className="flex flex-col gap-6">
          {/* 설정 메뉴 */}
          <section className="rounded-2xl border border-border bg-card shadow-sm overflow-hidden" aria-labelledby="settings-heading">
            <h2 id="settings-heading" className="sr-only">설정 메뉴</h2>
            {[
              { icon: User,       label: "프로필 정보", desc: "닉네임·투자 성향",   href: null,                      action: () => setEditing(true) },
              { icon: Bell,       label: "알림 설정",   desc: "채널·빈도·유형",    href: "/notifications/settings", action: null },
              { icon: CreditCard, label: "구독 관리",   desc: "플랜·결제 수단",    href: "/pricing",                action: null },
              { icon: Lock,       label: "계정 보안",   desc: "비밀번호·연결 계정", href: null,                      action: () => toast.info("비밀번호 변경 기능을 준비 중입니다.") },
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

          {/* 로그아웃 */}
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

          {/* 우측 컬럼 */}
          <div className="flex flex-col gap-6">
          {/* 개인정보·보안 */}
          <section className="rounded-2xl border border-border bg-card shadow-sm overflow-hidden" aria-labelledby="privacy-heading">
            <h2
              id="privacy-heading"
              className="border-b border-border px-5 py-4 text-[11px] font-extrabold uppercase tracking-widest text-primary"
            >
              개인정보 · 보안
            </h2>

            <Link
              href="/privacy"
              className="flex items-center gap-4 border-b border-border px-5 py-4 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
            >
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted">
                <Shield className="size-4 text-muted-foreground" aria-hidden />
              </div>
              <span className="flex-1 text-sm font-bold text-foreground">개인정보 처리방침</span>
              <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
            </Link>

            <button
              type="button"
              onClick={() => toast.info("내 데이터 다운로드 기능을 준비 중입니다.")}
              className="flex w-full items-center gap-4 border-b border-border px-5 py-4 text-left transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
            >
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted">
                <Download className="size-4 text-muted-foreground" aria-hidden />
              </div>
              <div className="flex-1">
                <p className="text-sm font-bold text-foreground">내 데이터 다운로드</p>
                <p className="text-xs text-muted-foreground">GDPR · 개인정보보호법</p>
              </div>
              <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
            </button>

            <button
              type="button"
              onClick={() => toast.info(`계정 삭제는 고객지원(${SUPPORT_EMAIL})에 문의해 주세요.`)}
              className="flex w-full items-center gap-4 px-5 py-4 text-left transition-colors hover:bg-destructive/5 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
            >
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-destructive/10">
                <Trash2 className="size-4 text-destructive" aria-hidden />
              </div>
              <div className="flex-1">
                <p className="text-sm font-bold text-destructive">계정 · 데이터 삭제</p>
                <p className="text-xs text-muted-foreground">되돌릴 수 없어요</p>
              </div>
              <ChevronRight className="size-4 text-destructive/50" aria-hidden />
            </button>
          </section>

          {/* 지원 */}
          <section className="rounded-2xl border border-border bg-card shadow-sm overflow-hidden" aria-labelledby="support-heading">
            <h2
              id="support-heading"
              className="border-b border-border px-5 py-4 text-[11px] font-extrabold uppercase tracking-widest text-primary"
            >
              지원
            </h2>

            <Link
              href="/support"
              className="flex items-center gap-4 px-5 py-4 transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-inset"
            >
              <div className="grid size-10 shrink-0 place-items-center rounded-xl bg-muted">
                <HelpCircle className="size-4 text-muted-foreground" aria-hidden />
              </div>
              <span className="flex-1 text-sm font-bold text-foreground">공지사항 · 고객센터</span>
              <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
            </Link>
          </section>

          {/* 앱 버전 — 카드 외부 독립 행 */}
          <div className="flex items-center justify-between px-1">
            <span className="text-sm text-muted-foreground">앱 버전</span>
            <span className="text-sm text-muted-foreground">{APP_VERSION}</span>
          </div>
          </div>
        </div>
      </div>
    </div>
  );
}
