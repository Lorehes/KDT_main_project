import Link from "next/link";
import { buttonVariants } from "@/components/ui/button";

const routes = [
  { href: "/signup", label: "회원가입" },
  { href: "/login", label: "로그인" },
  { href: "/dashboard", label: "대시보드" },
  { href: "/portfolios", label: "보유 종목" },
  { href: "/notifications", label: "알림" },
  { href: "/pricing", label: "요금제" },
];

export default function Home() {
  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col justify-center gap-8 p-8">
      <div className="space-y-3">
        <h1 className="text-3xl font-bold tracking-tight">DART 공시 통역 (DartCommons)</h1>
        <p className="text-muted-foreground">
          보유 종목의 DART 공시를 AI가 호재/악재로 해석해 실시간으로 알려주는 개인 투자자용 서비스.
        </p>
      </div>

      <nav className="flex flex-wrap gap-2" aria-label="주요 화면">
        {routes.map((r) => (
          <Link key={r.href} href={r.href} className={buttonVariants({ variant: "outline" })}>
            {r.label}
          </Link>
        ))}
      </nav>

      <p className="text-xs text-muted-foreground">
        본 서비스는 정보 제공 도구이며 투자 자문·권유가 아닙니다. AI 분석은 부정확할 수 있으며 투자
        책임은 이용자에게 있습니다.
      </p>
    </main>
  );
}
