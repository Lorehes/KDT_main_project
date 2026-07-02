"use client";

// [목적] 공시 상세 페이지(D3/m04·D17/m17·D18/m20) — Free·Pro·Premium 단계별 분석 + 피드백
//   Wave 1: 목업 리레이아웃(AI 인덱스·한 줄 요약·Premium 다크 CTA). Wave 2: 이런내용이에요/호재·악재 요인(Free).
//   Wave 3: 내 평균 매수가 박스(티어 무관) + 유사공시 v2(유사도 리스트). 예측 차트는 KRX 시계열 미구현으로 별도 Spec.
// [이유] 티어별 정보 노출: Free(판정+요약+요인), Pro(유사공시), Premium(재무·업황). 티어 미달 시 TierGate/다크 CTA
// [사이드 임팩트] disclosure 로드 완료 후 analysis 쿼리 활성(R7) — 직렬화로 미스매치 방지.
//   analysis null → disclosure.sentiment 폴백 대신 "분석 대기 중" 배지(R1, 자본시장법 §11.1).
//   usePortfolios로 보유 종목 매칭 — 매수가(복호화 PII)는 렌더만, 절대 로깅 금지(CLAUDE.md §7).
// [수정 시 고려사항] 원문 인용 필드(corp_name·report_nm·수치)는 LLM 변형 없이 그대로 렌더(CLAUDE.md §4).
//   is_withheld=true 또는 confidence<0.5 시 SentimentBadge를 WITHHELD로, AI 인덱스를 "신뢰도 낮음"으로 표시(투자자 보호 의무).
//   similar_disclosures는 v2(similarity_score) — priceReaction5dPct 제거됨. 예측 차트 부활은 KRX 시계열 Spec 후.
//   Premium financial_context는 현재 JSON 원시 출력 — Stage 5 구조화 UI로 교체 예정.

import { useParams } from "next/navigation";
import Link from "next/link";
import { ExternalLink, ArrowLeft, TrendingUp, TrendingDown } from "lucide-react";
import { useDisclosure, useDisclosureAnalysis, EXPECTED_REACTION_CONFIG } from "@/lib/api/disclosures";
import { useTierCheck } from "@/lib/hooks/useTierCheck";
import { useDelayedLoading } from "@/lib/hooks/useDelayedLoading";
import { useUIStore } from "@/lib/stores/uiStore";
import { Skeleton } from "@/components/ui/skeleton";
import { Button } from "@/components/ui/button";
import { SUPPORT_EMAIL, TIER_LABEL } from "@/lib/constants";
import { SentimentBadge } from "@/components/domain/SentimentBadge";
import { ConfidenceMeter } from "@/components/domain/ConfidenceMeter";
import { DisclaimerNotice } from "@/components/domain/DisclaimerNotice";
import { TierGate } from "@/components/domain/TierGate";
import { FeedbackPrompt } from "@/components/domain/FeedbackPrompt";
import { usePortfolios } from "@/lib/api/portfolios";

export default function DisclosureDetailPage() {
  const params = useParams<{ id: string }>();
  const id = Number(params.id);
  const { isPro, isPremium } = useTierCheck();
  const { setUpsellModalOpen } = useUIStore();

  const { data: disclosure, isLoading: discLoading } = useDisclosure(id);
  // R7: disclosure 로드 완료 후 분석 쿼리 활성 — 미스매치 데이터 렌더 방지
  const { data: analysis, isLoading: analysisLoading } = useDisclosureAnalysis(id, { enabled: !!disclosure });
  const showSkeleton = useDelayedLoading(discLoading || analysisLoading);

  // 내 평균 매수가 박스(Wave 3, 티어 무관) — 이 공시 종목을 보유 중이면 매수가 대비 현재가 손익 표시.
  // 매수가는 복호화된 금융 PII — 렌더만 하고 절대 로깅 금지(CLAUDE.md §7).
  const { data: portfolios } = usePortfolios();
  const holding = portfolios?.find((p) => p.stock_code === disclosure?.stock_code);
  // 매수가·현재가 둘 다 있고 매수가>0이어야 손익 계산 — 미입력(CSV 등록)·종가 미수집·매수가 0이면 박스 미노출(0 나눗셈 방지).
  const position =
    holding?.avg_buy_price != null && holding.avg_buy_price > 0 && holding.close_price != null
      ? {
          avg: holding.avg_buy_price,
          cur: holding.close_price,
          pnlPct: ((holding.close_price - holding.avg_buy_price) / holding.avg_buy_price) * 100,
        }
      : null;

  if (discLoading || analysisLoading) {
    if (!showSkeleton) return null;
    return (
      <div className="mx-auto max-w-4xl" role="status" aria-label="공시 상세 불러오는 중">
        <Skeleton className="mb-5 h-5 w-24" />
        <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
          <div className="flex flex-col gap-5">
            <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-6 shadow-sm">
              <div className="flex items-start justify-between gap-3">
                <div className="flex flex-col gap-2">
                  <Skeleton className="h-6 w-32" />
                  <Skeleton className="h-4 w-64" />
                  <Skeleton className="h-3 w-24" />
                </div>
                <Skeleton className="h-10 w-24" />
              </div>
            </div>
            <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-6 shadow-sm">
              <Skeleton className="h-3 w-20" />
              <Skeleton className="h-4 w-full" />
              <Skeleton className="h-4 w-3/4" />
              <Skeleton className="h-4 w-1/2" />
            </div>
          </div>
          <div className="hidden lg:flex lg:flex-col lg:gap-4">
            <div className="flex flex-col gap-3 rounded-2xl border border-border bg-card p-5 shadow-sm">
              <Skeleton className="h-3 w-12" />
              <Skeleton className="h-4 w-36" />
            </div>
          </div>
        </div>
      </div>
    );
  }

  if (!disclosure) {
    return (
      <div className="flex h-64 flex-col items-center justify-center gap-4 text-sm text-muted-foreground">
        <p>공시를 찾을 수 없습니다.</p>
        <Link href="/disclosures" className="font-bold text-primary hover:underline">← 공시 피드로 돌아가기</Link>
      </div>
    );
  }

  // is_withheld=true 또는 confidence<0.5 → WITHHELD (투자자 보호 — CLAUDE.md §6-6)
  const isWithheld = (analysis?.is_withheld ?? false) ||
    (analysis?.confidence !== undefined && analysis.confidence < 0.5);
  // R1: analysis 미완료 시 disclosure.sentiment(룰 기반) 노출 금지 — 자본시장법 §11.1
  // analysis가 존재할 때만 폴백 허용. null이면 undefined → SentimentBadge 미표시
  const sentiment = analysis ? (analysis.sentiment ?? disclosure.sentiment) : undefined;
  const reactionCfg = analysis?.expected_reaction
    ? EXPECTED_REACTION_CONFIG[analysis.expected_reaction] ?? EXPECTED_REACTION_CONFIG.FLAT
    : null;

  return (
    <div className="mx-auto max-w-4xl">
      <Link href="/disclosures" className="mb-5 inline-flex items-center gap-1.5 text-sm font-bold text-muted-foreground hover:text-foreground focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
        <ArrowLeft className="size-4" aria-hidden />
        공시 피드
      </Link>

      <div className="grid gap-6 lg:grid-cols-[1fr_340px]">
        {/* ── 메인 컨텐츠 ── */}
        <div className="flex flex-col gap-5">

          {/* 헤더 — 회사명·코드·제목은 DART 원본 그대로 렌더 (LLM 변형 금지). AI 인덱스 상단 우측 강조(목업) */}
          <div className="rounded-2xl border border-border bg-card p-6 shadow-sm">
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-xl font-extrabold text-foreground">{disclosure.corp_name}</span>
                  <span className="font-mono text-sm text-muted-foreground">{disclosure.stock_code}</span>
                  {sentiment
                    ? <SentimentBadge sentiment={sentiment} isWithheld={isWithheld} size="sm" />
                    : (
                      // R1: analysis 미완료 → 룰 기반 sentiment 대신 "분석 대기 중" 명시 (자본시장법 §11.1)
                      <span
                        className="inline-flex items-center rounded-full border border-border bg-muted px-2 py-0.5 text-[11px] font-bold text-muted-foreground"
                        role="status"
                        aria-label="AI 분석 대기 중"
                      >
                        분석 대기 중
                      </span>
                    )
                  }
                </div>
                <p className="mt-2 text-base font-bold leading-snug text-foreground">{disclosure.report_nm}</p>
                <time className="mt-1 block text-xs text-muted-foreground" dateTime={disclosure.rcept_dt}>
                  접수일 {disclosure.rcept_dt}
                </time>
              </div>

              {/* AI 인덱스 — confidence 시각화(ConfidenceMeter 재활용). withheld면 "신뢰도 낮음" 표기 */}
              {analysis?.confidence !== undefined && (
                <div className="flex shrink-0 flex-col items-end gap-1.5">
                  <span className="text-[11px] font-extrabold uppercase tracking-widest text-muted-foreground">AI 인덱스</span>
                  <ConfidenceMeter confidence={analysis.confidence} />
                </div>
              )}
            </div>
          </div>

          {/* Free — 한 줄 요약 헤드라인 (기존 summary 활용, Wave 2에서 headline 전용 필드로 분리 예정) */}
          {analysis && (
            <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="summary-heading">
              <h2 id="summary-heading" className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">AI 분석 요약</h2>
              {isWithheld ? (
                <p className="text-sm text-muted-foreground">AI 신뢰도가 낮아 판단을 보류합니다. DART 원문을 직접 확인하시기 바랍니다.</p>
              ) : (
                <>
                  <p className="text-[15px] leading-relaxed text-foreground">{analysis.summary}</p>
                  {reactionCfg && (
                    <div className={`mt-4 inline-flex items-center gap-2 rounded-lg px-3 py-2 text-sm font-bold ${reactionCfg.colorClass}`}>
                      {reactionCfg.label}
                      {analysis.rationale && <span className="font-normal text-muted-foreground"> · {analysis.rationale}</span>}
                    </div>
                  )}
                </>
              )}
            </section>
          )}

          {/* Free — 이런 내용이에요 (key_points, Stage 2). withheld/미보유 시 미노출 */}
          {analysis && !isWithheld && (analysis.key_points?.length ?? 0) > 0 && (
            <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="keypoints-heading">
              <h2 id="keypoints-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">이런 내용이에요</h2>
              <ol className="flex flex-col gap-3">
                {analysis.key_points!.map((point, i) => (
                  <li key={i} className="flex gap-3">
                    <span className="grid size-6 shrink-0 place-items-center rounded-full bg-primary/10 text-xs font-extrabold text-primary" aria-hidden>{i + 1}</span>
                    <p className="text-[15px] leading-relaxed text-foreground">{point}</p>
                  </li>
                ))}
              </ol>
            </section>
          )}

          {/* Free — 영향 요인 (호재/악재 2컬럼, Stage 2). 색+아이콘+텍스트 병용(a11y) */}
          {analysis && !isWithheld && ((analysis.positive_factors?.length ?? 0) > 0 || (analysis.negative_factors?.length ?? 0) > 0) && (
            <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="factors-heading">
              <h2 id="factors-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">영향 요인</h2>
              <div className="grid gap-4 sm:grid-cols-2">
                <FactorColumn
                  kind="positive"
                  items={analysis.positive_factors ?? []}
                />
                <FactorColumn
                  kind="negative"
                  items={analysis.negative_factors ?? []}
                />
              </div>
            </section>
          )}

          {/* 내 평균 매수가 (Wave 3, 티어 무관) — 보유 종목일 때만. 색+부호 병용(a11y), 정보 제공 톤(자본시장법) */}
          {position && (
            <section className="rounded-2xl border border-border bg-muted/30 p-6" aria-labelledby="position-heading">
              <h2 id="position-heading" className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">내 평균 매수가</h2>
              <div className="flex flex-wrap items-baseline gap-x-5 gap-y-1">
                <p className="text-sm text-muted-foreground">평균 매수가 <span className="font-bold text-foreground">{position.avg.toLocaleString("ko-KR")}원</span></p>
                <p className="text-sm text-muted-foreground">현재가 <span className="font-bold text-foreground">{position.cur.toLocaleString("ko-KR")}원</span></p>
                <p className={`text-sm font-extrabold ${position.pnlPct >= 0 ? "text-[color:var(--color-sentiment-positive)]" : "text-[color:var(--color-sentiment-negative)]"}`}>
                  평가손익 {position.pnlPct >= 0 ? "+" : ""}{position.pnlPct.toFixed(1)}%
                </p>
              </div>
              <p className="mt-2 text-xs text-muted-foreground">보유 종목 기준 참고용 정보이며, 투자 판단의 근거가 아닙니다.</p>
            </section>
          )}

          {/* Pro — 과거 유사 공시 (Stage 3 RAG 유사도). 주가 반응 차트는 KRX 시계열 구현 후 별도 Spec */}
          <section aria-labelledby="pro-heading">
            <h2 id="pro-heading" className="mb-3 flex items-center gap-2 text-[11px] font-extrabold uppercase tracking-widest text-primary">
              과거 유사 공시
              <span className="rounded-md bg-primary px-1.5 py-0.5 text-[10px] text-primary-foreground">Pro</span>
            </h2>
            {isPro && analysis?.similar_disclosures?.length ? (
              <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
                <ul className="divide-y divide-border" aria-label="유사 공시 목록">
                  {analysis.similar_disclosures.map((s) => (
                    <li key={s.rcept_no} className="py-3 first:pt-0 last:pb-0">
                      <Link href={`/disclosures/${s.disclosure_id}`} className="group flex items-center justify-between gap-3 focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring">
                        <div className="min-w-0">
                          <p className="truncate text-sm font-bold text-foreground group-hover:underline">{s.corp_name}</p>
                          <time className="font-mono text-xs text-muted-foreground" dateTime={s.rcept_dt}>{s.rcept_dt}</time>
                        </div>
                        <span className="shrink-0 rounded-full bg-muted px-2.5 py-1 text-xs font-bold text-muted-foreground">
                          유사도 {Math.round(s.similarity_score * 100)}%
                        </span>
                      </Link>
                    </li>
                  ))}
                </ul>
              </div>
            ) : !isPro ? (
              <TierGate requiredTier="PRO">
                <div className="h-40 rounded-xl bg-muted/40 p-4 text-xs text-muted-foreground">과거 유사 공시 (Pro 전용)</div>
              </TierGate>
            ) : (
              <p className="text-sm text-muted-foreground">유사 공시 데이터가 없습니다.</p>
            )}
          </section>

          {/* Premium — 재무 영향 + 업황. 미달 시 다크 네이비 CTA 카드(목업) */}
          <section aria-labelledby="premium-heading">
            <h2 id="premium-heading" className="mb-3 flex items-center gap-2 text-[11px] font-extrabold uppercase tracking-widest text-primary">
              재무·업황 심층 분석
              <span className="rounded-md bg-[color:var(--color-sentiment-withheld)] px-1.5 py-0.5 text-[10px] text-[color:var(--color-sentiment-withheld-foreground)]">Premium</span>
            </h2>
            {isPremium && analysis?.financial_context ? (
              <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
                {/* Wave 2에서 구조화된 재무 테이블·업황 비교 UI로 교체 예정 */}
                <pre className="whitespace-pre-wrap text-sm text-foreground">
                  {JSON.stringify(analysis.financial_context, null, 2)}
                </pre>
              </div>
            ) : !isPremium ? (
              <div className="flex flex-col gap-3 rounded-2xl bg-[color:var(--color-brand-navy)] p-6 sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0">
                  <p className="text-base font-extrabold text-white">재무·업황 보러가기</p>
                  <p className="mt-1 text-sm text-white/70">과거 재무 추이·업황 비교 심층 분석은 {TIER_LABEL.PREMIUM} 플랜에서 제공됩니다.</p>
                </div>
                <Button
                  variant="secondary"
                  size="sm"
                  className="shrink-0"
                  onClick={() => setUpsellModalOpen(true)}
                  aria-label={`${TIER_LABEL.PREMIUM} 플랜으로 업그레이드`}
                >
                  {TIER_LABEL.PREMIUM} 업그레이드하기 →
                </Button>
              </div>
            ) : null}
          </section>

          {/* 피드백 — 모든 공시 상세에 상시 노출 의무 (CLAUDE.md §6-6) */}
          {analysis && (
            <section className="rounded-2xl border border-border bg-card p-6 shadow-sm" aria-labelledby="feedback-heading">
              <h2 id="feedback-heading" className="mb-4 text-[11px] font-extrabold uppercase tracking-widest text-primary">분석 피드백</h2>
              <FeedbackPrompt analysisId={analysis.analysis_id} />
            </section>
          )}
        </div>

        {/* ── 사이드바 (웹 전용) ── */}
        <aside className="hidden lg:flex lg:flex-col lg:gap-4">
          {disclosure.attachment_url && (
            <div className="rounded-2xl border border-border bg-card p-5 shadow-sm">
              <p className="mb-3 text-[11px] font-extrabold uppercase tracking-widest text-primary">원문</p>
              <a href={disclosure.attachment_url} target="_blank" rel="noopener noreferrer"
                className="flex items-center gap-2 text-sm font-bold text-primary hover:underline focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
                aria-label="DART 원문 열기 (새 탭)">
                <ExternalLink className="size-4" aria-hidden />
                DART 공시 원문 보기
              </a>
            </div>
          )}

          {/* 면책 고지 — 모든 분석 화면 상시 노출 필수 (CLAUDE.md §6-6) */}
          <DisclaimerNotice reportPath={analysis?.report_inaccuracy_path ?? `mailto:${SUPPORT_EMAIL}`} />

          {analysis && (
            <div className="rounded-2xl border border-border bg-card p-5 shadow-sm text-xs text-muted-foreground">
              <p className="mb-2 font-extrabold uppercase tracking-widest text-primary">분석 정보</p>
              <dl className="flex flex-col gap-1.5">
                <div className="flex justify-between"><dt>분석 단계</dt><dd className="font-bold text-foreground">Stage {analysis.stage_reached}</dd></div>
                <div className="flex justify-between"><dt>분석 시각</dt><dd className="font-bold text-foreground">{new Date(analysis.created_at).toLocaleString("ko-KR")}</dd></div>
              </dl>
            </div>
          )}
        </aside>
      </div>

      {/* 모바일 면책 고지 — analysis 없어도 신고 경로 표시(CLAUDE.md §6-6 신고 경로 동반 의무) */}
      <div className="mt-6 lg:hidden">
        <DisclaimerNotice reportPath={analysis?.report_inaccuracy_path ?? `mailto:${SUPPORT_EMAIL}`} />
      </div>
    </div>
  );
}

// [목적] 영향 요인 단일 컬럼(호재 또는 악재) — 색+아이콘+텍스트 3중 표기로 색맹 배려(CLAUDE.md §6-5)
// [이유] 한국 증시 관행: 호재=빨강(positive)/악재=파랑(negative) 토큰. 색상 단독 의미 전달 금지(WCAG 2.1 AA)
// [사이드 임팩트] 공시 상세 Free 영향 요인 섹션에서만 사용. sentiment 토큰 변경 시 함께 반영
// [수정 시 고려사항] items 빈 배열이면 "해당 없음" 표기 — 부모가 두 컬럼 모두 빈 경우 섹션 자체를 미노출
function FactorColumn({ kind, items }: { kind: "positive" | "negative"; items: string[] }) {
  const isPositive = kind === "positive";
  const Icon = isPositive ? TrendingUp : TrendingDown;
  const label = isPositive ? "호재 요인" : "악재 요인";
  // 완전한 리터럴 클래스 — Tailwind JIT가 감지하도록 조건부로 전체 문자열 선택
  const textClass = isPositive
    ? "text-[color:var(--color-sentiment-positive)]"
    : "text-[color:var(--color-sentiment-negative)]";
  const dotClass = isPositive
    ? "bg-[color:var(--color-sentiment-positive)]"
    : "bg-[color:var(--color-sentiment-negative)]";

  return (
    <div className="rounded-xl border border-border bg-muted/30 p-4">
      <div className="mb-3 flex items-center gap-1.5">
        <Icon className={`size-4 ${textClass}`} aria-hidden />
        <span className={`text-sm font-extrabold ${textClass}`}>{label}</span>
      </div>
      {items.length > 0 ? (
        <ul className="flex flex-col gap-2">
          {items.map((item, i) => (
            <li key={i} className="flex gap-2 text-sm leading-relaxed text-foreground">
              <span className={`mt-2 size-1.5 shrink-0 rounded-full ${dotClass}`} aria-hidden />
              {item}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-sm text-muted-foreground">해당 없음</p>
      )}
    </div>
  );
}
