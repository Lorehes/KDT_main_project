"use client";

// [목적] 가입 완료 화면(D10/m14) — 환영 메시지 + 시작 체크리스트로 첫 행동 유도
// [이유] 온보딩 완료 확인 + 종목 등록·알림 설정 행동으로 사용자를 즉시 앱 핵심 기능으로 안내
// [사이드 임팩트] signupStore.clear() 호출로 메모리의 이메일·비밀번호 제거. authStore에서 user 읽어 닉네임 표시
// [수정 시 고려사항] 온보딩 완료 이벤트 로깅(analytics) 추가 시 이 페이지에서 호출

import Link from "next/link";
import { useEffect } from "react";
import { Check } from "lucide-react";
import { buttonVariants } from "@/components/ui/button";
import { useAuthStore } from "@/lib/stores/authStore";
import { useSignupStore } from "@/lib/stores/signupStore";

export default function CompletePage() {
  const { user, fetchMe } = useAuthStore();
  const { clear } = useSignupStore();

  useEffect(() => {
    clear(); // 메모리에서 비밀번호 등 민감 데이터 제거
    fetchMe(); // 최신 user 정보 로드
  // clear·fetchMe는 안정적 참조
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const nickname = user?.nickname ?? "투자자";

  return (
    <div className="flex min-h-screen items-start justify-center bg-muted/30 p-6 pt-12 md:items-center">
      <div className="flex w-full max-w-[540px] flex-col items-center gap-7 text-center">
        {/* 체크 아이콘 */}
        <div className="grid size-24 place-items-center rounded-full bg-[color:var(--color-sentiment-positive)]" aria-hidden>
          <Check className="size-11 text-white" strokeWidth={2.8} />
        </div>

        <div>
          <h1 className="text-4xl font-extrabold tracking-tight text-foreground">
            환영합니다,<br />{nickname}님!
          </h1>
          <p className="mt-3 text-base text-muted-foreground">
            이제 보유 종목만 등록하면 공시 해석이<br />카카오 알림톡과 웹 대시보드로 도착해요.
          </p>
        </div>

        {/* 시작 체크리스트 */}
        <div className="w-full rounded-2xl border border-border bg-card p-5 text-left">
          <p className="mb-4 text-sm font-extrabold text-foreground">시작 체크리스트</p>
          <ul className="flex flex-col gap-3">
            <ChecklistItem done label="계정 만들기" sub="완료" />
            <ChecklistItem done={false} label="보유 종목 등록" sub="3개까지 무료" action={
              <Link href="/portfolios" className={buttonVariants({ size: "sm" }) + " shrink-0"}>
                등록
              </Link>
            } />
            <ChecklistItem done={false} label="알림 채널 설정" sub="카카오 알림톡" action={
              <Link href="/notifications/settings" className={buttonVariants({ variant: "outline", size: "sm" }) + " shrink-0"}>
                설정
              </Link>
            } />
          </ul>
        </div>

        <Link href="/portfolios" className={buttonVariants({ size: "lg" }) + " w-full max-w-xs"}>
          첫 종목 등록하기 →
        </Link>
        <Link href="/dashboard" className="text-sm text-muted-foreground hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
          대시보드로 바로 이동
        </Link>
      </div>
    </div>
  );
}

function ChecklistItem({ done, label, sub, action }: { done: boolean; label: string; sub: string; action?: React.ReactNode }) {
  return (
    <li className="flex items-center gap-3">
      <span className={`grid size-6 shrink-0 place-items-center rounded-[7px] border-2 ${done ? "border-primary bg-primary" : "border-border"}`} aria-hidden>
        {done && <Check className="size-3.5 text-white" strokeWidth={2.8} />}
      </span>
      <span className="flex-1">
        <span className="block text-sm font-bold text-foreground">{label}</span>
        <span className="block text-xs text-muted-foreground">{sub}</span>
      </span>
      {action}
    </li>
  );
}
