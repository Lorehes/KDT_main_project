"use client";

// [목적] 비로그인 대시보드 미리보기(D2 preview) — 랜딩 "대시보드 둘러보기" CTA 도착지.
//        목업 데이터로 실제 대시보드 UX를 체험하게 하고 가입으로 전환 유도.
// [이유] 로그인 없이도 앱 가치를 직접 체험(AARRR Activation) — 전환율 상승.
//        미들웨어에서 /dashboard/preview를 PUBLIC_PATHS에 추가해 인증 우회.
// [사이드 임팩트] AppShell이 NotificationModal/TopBar 등을 마운트하나 user=null이므로 graceful degradation.
//               목업 데이터는 이 파일에만 정의 — 실제 API 호출 없음.
//               PnlStatCard 목업값(+1,234,000원/+3.4%)은 실제 대시보드와 동일 컴포넌트로 UX 일관성 유지
//               (한국 시장 컨벤션 상승=빨강 ▲, WCAG 색+아이콘+텍스트 3중 표현 자동 적용).
// [수정 시 고려사항] 실제 최신 DART 공시로 목업 교체 시 이 파일의 MOCK_DISCLOSURES 수정.
//                  가입 전환 CTA 문구는 A/B 테스트 대상.

import Link from "next/link";
import { X } from "lucide-react";
import { useState } from "react";
import { buttonVariants } from "@/components/ui/button";
import { DisclosureCard } from "@/components/domain/DisclosureCard";
import { StatCard, SentimentStatCard, PnlStatCard } from "@/components/domain/StatCards";
import type { Disclosure } from "@/lib/api/disclosures";

const MOCK_DISCLOSURES: Disclosure[] = [
  {
    id: 1,
    rcept_no: "20260611900001",
    corp_name: "삼성전자",
    stock_code: "005930",
    report_nm: "반기보고서 (2026.06)",
    rcept_dt: "20260611",
    sentiment: "POSITIVE",
    confidence: 0.91,
    is_withheld: false,
    summary: "2분기 영업이익 14.2조 원으로 전분기 대비 38% 증가. HBM3E 수요 급증에 따른 메모리 ASP 상승이 주요 요인. 하반기 AI 서버향 수주 확대 전망.",
  },
  {
    id: 2,
    rcept_no: "20260611900002",
    corp_name: "SK하이닉스",
    stock_code: "000660",
    report_nm: "주요경영사항(자율공시) — HBM4 양산 개시",
    rcept_dt: "20260611",
    sentiment: "POSITIVE",
    confidence: 0.87,
    is_withheld: false,
    summary: "HBM4(6세대 고대역폭 메모리) 세계 최초 양산 돌입. 엔비디아 블랙웰 차기 GPU에 독점 공급 예정. 2027년 HBM 시장점유율 60% 목표 제시.",
  },
  {
    id: 3,
    rcept_no: "20260611900003",
    corp_name: "카카오",
    stock_code: "035720",
    report_nm: "임원·주요주주 특정증권 소유상황 보고",
    rcept_dt: "20260611",
    sentiment: "NEUTRAL",
    confidence: 0.72,
    is_withheld: false,
    summary: "대표이사 보유 주식 일부 매각 신고. 재무적 목적의 일상적 거래로 경영권 변동 없음. 지분율 0.08%p 감소.",
  },
  {
    id: 4,
    rcept_no: "20260611900004",
    corp_name: "LG에너지솔루션",
    stock_code: "373220",
    report_nm: "단기차입금 변동(증가) — 운영자금 조달",
    rcept_dt: "20260610",
    sentiment: "NEGATIVE",
    confidence: 0.68,
    is_withheld: false,
    summary: "단기차입금 6,500억 원 증가. 북미 신규 배터리 공장 증설 자금 마련 목적. 부채비율 일시 상승 예상되나 장기 캐팩스 선투자 성격.",
  },
  {
    id: 5,
    rcept_no: "20260611900005",
    corp_name: "현대자동차",
    stock_code: "005380",
    report_nm: "배당결정 — 분기 배당 실시",
    rcept_dt: "20260610",
    sentiment: "POSITIVE",
    confidence: 0.83,
    is_withheld: false,
    summary: "1주당 분기배당 2,000원 결정 (전년 동기 대비 33% 상향). 주주환원 정책 강화 기조 유지. 배당락일 6월 27일.",
  },
  {
    id: 6,
    rcept_no: "20260611900006",
    corp_name: "POSCO홀딩스",
    stock_code: "005490",
    report_nm: "타법인 주식취득 결정 — 이차전지소재 자회사 지분 추가 취득",
    rcept_dt: "20260610",
    sentiment: "NEUTRAL",
    confidence: 0.61,
    is_withheld: true,
    summary: "판단 보류 — 신뢰도 낮음. 취득 규모·시너지 불확실. 추가 공시 확인 권장.",
  },
];

const MOCK_PORTFOLIOS = ["삼성전자", "SK하이닉스", "카카오", "LG에너지솔루션"];

export default function DashboardPreviewPage() {
  const [bannerVisible, setBannerVisible] = useState(true);

  const positiveCount = MOCK_DISCLOSURES.filter((d) => !d.is_withheld && d.sentiment === "POSITIVE").length;
  const neutralCount = MOCK_DISCLOSURES.filter((d) => !d.is_withheld && d.sentiment === "NEUTRAL").length;
  const negativeCount = MOCK_DISCLOSURES.filter((d) => !d.is_withheld && d.sentiment === "NEGATIVE").length;
  const withheldCount = MOCK_DISCLOSURES.filter((d) => d.is_withheld === true).length;

  return (
    <div className="flex flex-col gap-6">
      {/* 미리보기 배너 */}
      {bannerVisible && (
        <div className="flex items-center justify-between gap-3 rounded-2xl border border-[color:var(--color-brand-blue)]/30 bg-[color:var(--color-brand-blue)]/5 px-5 py-3.5">
          <div className="flex items-center gap-3">
            <span className="shrink-0 rounded-md bg-[color:var(--color-brand-blue)] px-2 py-0.5 text-[11px] font-extrabold text-white">
              미리보기
            </span>
            <p className="text-sm text-foreground">
              <span className="font-bold">목업 데이터</span>로 체험 중입니다.
              실제 내 보유 종목 공시를 받으려면{" "}
              <Link href="/signup" className="font-bold text-[color:var(--color-brand-blue)] underline underline-offset-2">
                무료 가입
              </Link>
              하세요.
            </p>
          </div>
          <button
            type="button"
            onClick={() => setBannerVisible(false)}
            aria-label="배너 닫기"
            className="shrink-0 rounded-lg p-1 text-muted-foreground hover:bg-muted focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
          >
            <X className="size-4" />
          </button>
        </div>
      )}

      {/* 헤더 */}
      <div className="flex items-start justify-between">
        <div>
          <p className="text-xs font-extrabold uppercase tracking-widest text-primary">Good morning</p>
          <h1 className="mt-1 text-2xl font-extrabold tracking-tight text-foreground">
            오늘의 내 공시 레이더
          </h1>
          <p className="mt-1 text-sm text-muted-foreground">
            보유 종목: {MOCK_PORTFOLIOS.join(", ")}
          </p>
        </div>
        <Link href="/signup" className={buttonVariants({ size: "sm" }) + " shrink-0"}>
          무료 가입 →
        </Link>
      </div>

      {/* 통계 카드 4종 — 호재/악재/보류는 1개 카드로 통합.
          평가 손익은 PnlStatCard 목업(+3.4%, +1,234,000원) — 실제 대시보드와 동일 컴포넌트로 일관성 유지. */}
      <ul className="grid grid-cols-2 gap-4 lg:grid-cols-4" aria-label="오늘 공시 통계">
        <StatCard label="오늘 공시" value={MOCK_DISCLOSURES.length} unit="건" />
        <SentimentStatCard
          positive={positiveCount}
          neutral={neutralCount}
          negative={negativeCount}
          withheld={withheldCount}
        />
        <StatCard label="보유 종목" value={MOCK_PORTFOLIOS.length} unit="종목" />
        <PnlStatCard
          pnl={1_234_000}
          pnlRate={3.4}
          asOf="2026-06-11"
          unpricedCount={0}
        />
      </ul>

      {/* 공시 피드 */}
      <section aria-label="보유 종목 공시 피드">
        <div className="mb-4 flex items-center justify-between">
          <h2 className="text-base font-extrabold text-foreground">최신 공시</h2>
          <span className="text-xs text-muted-foreground">목업 데이터 · 실제 데이터는 가입 후</span>
        </div>
        <ul className="flex flex-col gap-3">
          {MOCK_DISCLOSURES.map((d) => (
            <li key={d.id}>
              <DisclosureCard disclosure={d} />
            </li>
          ))}
        </ul>
      </section>

      {/* 하단 CTA */}
      <div className="rounded-2xl border border-[color:var(--color-brand-navy)] bg-[color:var(--color-brand-navy)] px-6 py-5 text-white">
        <p className="text-xs font-extrabold uppercase tracking-widest text-[color:var(--color-brand-sky)]">
          지금 바로 시작
        </p>
        <h2 className="mt-1 text-lg font-extrabold">
          내 보유 종목 공시를 실시간으로 받아보세요
        </h2>
        <p className="mt-1 text-sm text-blue-200">
          무료 플랜으로 종목 3개, 하루 5건 알림. 카카오·구글 1초 가입.
        </p>
        <Link
          href="/signup"
          className={buttonVariants({ size: "sm" }) + " mt-4 bg-[color:var(--color-brand-blue)] text-white hover:opacity-90"}
        >
          3개 종목 무료로 시작하기 →
        </Link>
      </div>
    </div>
  );
}
