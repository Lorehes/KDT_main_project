"use client";

// [목적] 가입 진입 화면(D5/m02) — 소셜·이메일 가입 분기점
// [이유] 신규 사용자의 첫 온보딩 단계. 소셜 우선 + 이메일 폴백 구조
// [사이드 임팩트] signupStore에 이메일·비밀번호 저장 후 /signup/verify로 이동. AuthLayout 스플릿 적용
// [수정 시 고려사항] 소셜 OAuth URL은 GET /auth/oauth/{provider}/url로 취득. 현재 placeholder 처리.
//   "정보 제공 도구, 투자자문 아님" 고지는 폼 하단에 상시 노출 (자본시장법 §11.1)

import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { AuthLayout } from "@/components/layout/AuthLayout";
import { Button } from "@/components/ui/button";
import { signupSchema, type SignupFormValues } from "@/lib/schemas/authSchemas";
import { useSignupStore } from "@/lib/stores/signupStore";

export default function SignupPage() {
  const router = useRouter();
  const { setEmail, setPassword } = useSignupStore();

  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<SignupFormValues>({
    resolver: zodResolver(signupSchema as any), // Zod v4 ↔ @hookform/resolvers 타입 충돌 억제 (런타임 정상)
  });

  const onSubmit = (data: SignupFormValues) => {
    setEmail(data.email);
    setPassword(data.password);
    router.push("/signup/verify");
  };

  return (
    <AuthLayout
      heading={<>3개 종목,<br /><span className="text-[color:var(--color-brand-sky)]">무료로 시작</span></>}
      subtext="가입 1분이면 첫 공시 해석을 받아볼 수 있어요. 카카오·구글로 즉시 시작하거나 이메일로 가입하세요."
    >
      <div className="flex flex-col gap-6">
        <div>
          <p className="text-xs font-extrabold uppercase tracking-widest text-primary">Welcome</p>
          <h1 className="mt-2 text-3xl font-extrabold tracking-tight text-foreground">시작하기</h1>
        </div>

        <div className="flex flex-col gap-2.5">
          <button type="button" onClick={() => alert("카카오 OAuth — 백엔드 연동 후 활성화")}
            className="flex w-full items-center justify-center gap-2.5 rounded-xl bg-[#FEE500] py-3 text-sm font-bold text-[#3C1E1E] transition-opacity hover:opacity-90 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label="카카오로 1초 시작">
            <span className="grid size-[22px] place-items-center rounded-md bg-[#3C1E1E]" aria-hidden>
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="#FEE500" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20.5 12a8 8 0 0 1-11.5 7.2L4 21l1.8-4.5A8 8 0 1 1 20.5 12z" />
              </svg>
            </span>
            카카오로 1초 시작
          </button>
          <button type="button" onClick={() => alert("구글 OAuth — 백엔드 연동 후 활성화")}
            className="flex w-full items-center justify-center gap-2.5 rounded-xl border border-border bg-background py-3 text-sm font-bold text-foreground transition-colors hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            aria-label="Google로 계속하기">
            <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden>
              <path fill="#4285F4" d="M21.6 12.2c0-.6-.1-1.2-.2-1.8H12v3.4h5.4a4.6 4.6 0 0 1-2 3v2.5h3.2c1.9-1.7 3-4.3 3-7.1z"/>
              <path fill="#34A853" d="M12 22c2.7 0 5-.9 6.6-2.4l-3.2-2.5c-.9.6-2 .9-3.4.9-2.6 0-4.8-1.7-5.6-4.1H3.1v2.6A10 10 0 0 0 12 22z"/>
              <path fill="#FBBC05" d="M6.4 13.9a6 6 0 0 1 0-3.8V7.5H3.1a10 10 0 0 0 0 9z"/>
              <path fill="#EA4335" d="M12 6c1.5 0 2.8.5 3.8 1.5l2.8-2.8A10 10 0 0 0 3.1 7.5l3.3 2.6C7.2 7.7 9.4 6 12 6z"/>
            </svg>
            Google로 계속하기
          </button>
        </div>

        <div className="flex items-center gap-3 text-xs text-muted-foreground">
          <span className="flex-1 border-t border-border" aria-hidden />또는 이메일로<span className="flex-1 border-t border-border" aria-hidden />
        </div>

        <form onSubmit={handleSubmit(onSubmit)} className="flex flex-col gap-4" noValidate>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="email" className="text-sm font-semibold text-foreground">이메일</label>
            <input id="email" type="email" autoComplete="email" placeholder="name@example.com"
              aria-invalid={!!errors.email} aria-describedby={errors.email ? "email-error" : undefined}
              className="rounded-xl border border-border bg-background px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:border-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/20 aria-invalid:border-destructive"
              {...register("email")} />
            {errors.email && <p id="email-error" className="text-xs text-destructive" role="alert">{errors.email.message}</p>}
          </div>
          <div className="flex flex-col gap-1.5">
            <label htmlFor="password" className="text-sm font-semibold text-foreground">비밀번호</label>
            <input id="password" type="password" autoComplete="new-password" placeholder="8자 이상 입력"
              aria-invalid={!!errors.password} aria-describedby={errors.password ? "pw-error" : undefined}
              className="rounded-xl border border-border bg-background px-4 py-3 text-sm text-foreground placeholder:text-muted-foreground focus-visible:border-primary focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary/20 aria-invalid:border-destructive"
              {...register("password")} />
            {errors.password && <p id="pw-error" className="text-xs text-destructive" role="alert">{errors.password.message}</p>}
          </div>
          <Button type="submit" className="w-full" disabled={isSubmitting}>
            {isSubmitting ? "처리 중..." : "가입하고 시작하기"}
          </Button>
        </form>

        {/* 자본시장법 §11.1 — 면책 고지 상시 노출 필수 */}
        <p className="text-center text-xs text-muted-foreground">
          가입 시 본 서비스가{" "}
          <strong className="font-semibold text-foreground">투자 자문이 아닌 정보 제공 도구</strong>임에 동의하게 됩니다.
          <br /><span className="text-[11px]">자본시장법 제6조·제17조상 투자자문업에 해당하지 않습니다.</span>
        </p>

        <p className="text-center text-sm text-muted-foreground">
          이미 계정이 있으신가요?{" "}
          <Link href="/login" className="font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">로그인</Link>
        </p>
      </div>
    </AuthLayout>
  );
}
